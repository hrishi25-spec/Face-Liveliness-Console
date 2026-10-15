#!/usr/bin/env node
/**
 * Project-boundary gate.
 *
 * This repository holds several independent projects (see AGENTS.md's project
 * map), and three failure modes have shipped here before because nothing
 * checked for them:
 *
 *   1. A project tree lands with no guidance of its own and no entry in the
 *      root project map, so the next reader cannot tell which rules apply and
 *      falls back to whatever conventions the request happened to mention.
 *   2. Instructions leak across a boundary: build or test commands -- and whole
 *      paragraphs -- copied from one project into another project's README or
 *      AGENTS file, telling readers to edit files that do not exist there.
 *   3. A verification gate exists but only one of the two halves knows about
 *      it. It runs on every push and is written down nowhere -- the console DOM
 *      harness and the cold-boot gate both spent time in that state -- or the
 *      reverse, where a README tells you to run a command no CI job ever runs,
 *      so the command rots and the doc lies.
 *
 * Rules
 *   R1  Every project tree carries guidance: an AGENTS.md at its root or
 *       inside it.
 *   R2  Every project tree, and every directory holding an AGENTS.md, is named
 *       in the root AGENTS.md project map.
 *   R3  A project's README/AGENTS files do not carry another project kind's
 *       toolchain: source paths, commands, and framework names from a different
 *       language ecosystem.
 *   R4  Every verification command a scope's guidance documents is run by one
 *       of the repository's CI workflows, so a documented gate cannot rot into
 *       a command that nothing executes.
 *   R5  Every CI step that runs a verification gate is documented in some
 *       project's guidance, so a gate cannot exist without anyone having
 *       written down what it is or how to run it locally.
 *
 *   R4 and R5 are the two halves of one invariant: the documented gates and
 *   the CI gates are the same set. R4 alone would still let an undocumented CI
 *   gate appear; R5 alone would still let a documented command go unrun.
 *
 * Scope
 *   - Scopes are discovered from the working tree: a top-level directory that
 *     contains program sources (or a package marker) is a project, each
 *     directory holding an AGENTS.md is its own scope, and each scope is
 *     scanned on its own -- a parent never re-scans a nested project, so a
 *     finding is always attributed to the tree that owns it. Kinds come from
 *     marker files (pom.xml -> maven, pubspec.yaml -> flutter,
 *     requirements.txt/pyproject.toml -> python, package.json -> node), with a
 *     source-extension fallback, so adding a project needs no edit here.
 *   - `docs/` is exempt from R3: it is the repository's cross-project
 *     documentation and legitimately names every project's files. The root
 *     AGENTS.md is exempt because R2 checks against it.
 *   - R3 targets instructions, not context. It flags commands and paths
 *     (`./mvnw test`, `app/services/*_engine.py`, `flutter pub get`), not prose
 *     that names another project's runtime -- an Android README explaining that
 *     the engine jar comes from the Maven/Spring build is correct and stays
 *     unflagged. `.java` is likewise not policed, because the Android harness
 *     legitimately compiles the shared engine's Java sources.
 *   - R4/R5 read commands out of code fences and inline code spans, and out of
 *     the `run:` steps of `.github/workflows/*.yml`. A line only counts as a
 *     command when it starts with a known runner or a `./` path, and only as a
 *     *verification gate* when it names a verification word (`test`, `check`,
 *     `lint`, `format`, `analyze`, `verify`, `smoke`, `guards`, ...) or invokes
 *     a checked-in harness under `scripts/`, `tool/`, `tests/`, or a `*.test.*`
 *     file. That keeps payloads, JSON samples and prose inside code fences from
 *     being read as commands. Comparison ignores flags and arguments: a
 *     documented `python -m pytest -q` is satisfied by CI's
 *     `python -m pytest --quiet`, and any invocation of the same script matches
 *     whatever arguments CI passes it.
 *   - Build output and dependency trees are never scanned.
 *
 * Escape hatch
 *   A line the gate flags -- an instruction that legitimately crosses a
 *   boundary, or a documented command that deliberately has no CI step of its
 *   own because a job runs it through a suite -- can stay if the line carries
 *   `boundary-allow: <reason>`. Escapes are printed with their reason, so they
 *   stay visible instead of silently weakening the gate.
 *
 *   A project that is deliberately outside CI coverage declares it in the root
 *   map: the bullet naming that project carries `boundary-allow-no-ci:
 *   <reason>`. Those scopes are exempt from R4 only (never from R5), and the
 *   exemption is printed with its reason like every other escape.
 *
 * Usage
 *   node scripts/check-project-boundaries.mjs                        # exits 1 on violations
 *   BOUNDARIES_ROOT=<dir> node scripts/check-project-boundaries.mjs  # fixtures
 */

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(process.env.BOUNDARIES_ROOT ?? join(SCRIPT_DIR, '..'));

/** Directories that are never projects, never scopes, and never scanned. */
const IGNORED_DIRS = new Set([
  '.git', '.github', '.claude', '.claude-flow', '.freebuff', '.mvn', '.vercel',
  // A local scratch renderer for README.md, not a project: it ships one script
  // and its own generated HTML, and the rendered output says nothing the
  // committed README does not.
  '.readme-preview',
  '.gradle', '.dart_tool', '.idea', '.venv', 'venv', 'node_modules',
  'target', 'build', 'dist', 'out', '__pycache__', 'screenshots',
  '.pytest_cache', '.ruff_cache', '.mypy_cache', '.cache', 'htmlcov',
  'coverage', '.pub-cache', '.terraform', 'MOSSIP-Vibe-Coded',
]);

/**
 * Top-level repository tooling: it has no boundary of its own (no guidance,
 * no map entry) and is deliberately not treated as a project.
 */
const TOOLING_DIRS = new Set(['scripts', 'tools', 'bin']);

/** Extensions that mark a directory as containing program source. */
const SOURCE_EXTENSIONS = new Set([
  '.java', '.kt', '.kts', '.dart', '.py', '.rb', '.php', '.cs', '.go', '.rs',
  '.c', '.cc', '.cpp', '.h', '.hpp', '.swift', '.scala', '.js', '.mjs', '.cjs',
  '.jsx', '.ts', '.tsx', '.vue', '.svelte',
]);

/** Marker file -> project kind. */
const KIND_MARKERS = [
  ['pom.xml', 'maven'],
  ['pubspec.yaml', 'flutter'],
  ['package.json', 'node'],
  ['requirements.txt', 'python'],
  ['pyproject.toml', 'python'],
  ['setup.py', 'python'],
  ['Cargo.toml', 'rust'],
  ['go.mod', 'go'],
];

/** Source extension -> kind, used when no marker file is present. */
const EXTENSION_KINDS = new Map([
  ['.py', 'python'],
  ['.dart', 'flutter'],
  ['.kt', 'flutter'],
  ['.java', 'maven'],
  ['.ts', 'node'],
  ['.tsx', 'node'],
  ['.js', 'node'],
  ['.mjs', 'node'],
  ['.cjs', 'node'],
]);

/**
 * R3 vocabulary. Each rule names the project kinds allowed to contain it; a
 * document in any other kind's scope is a violation.
 */
const TOKEN_RULES = [
  {
    id: 'python-source',
    allowed: ['python'],
    pattern: /[A-Za-z0-9_./-]+\.py\b/,
    hint: 'Python module path',
  },
  {
    id: 'python-framework',
    allowed: ['python'],
    pattern: /\b(?:Pydantic|Alembic|FastAPI|SQLAlchemy|uvicorn|pytest)\b/,
    hint: 'Python ecosystem name',
  },
  {
    id: 'python-naming',
    allowed: ['python'],
    pattern: /\b[a-z][a-z0-9]*_[a-z0-9_]+\s*\(\s*\)/,
    hint: 'snake_case call, Python naming convention',
  },
  {
    id: 'flutter-dart',
    allowed: ['flutter'],
    pattern: /[A-Za-z0-9_./-]+\.dart\b|\b(?:flutter|dart)\s+(?:pub|run|build|analyze|format|doctor|test|create|upgrade|--version)\b/,
    hint: 'Flutter/Dart path or command',
  },
  {
    id: 'kotlin-source',
    allowed: ['flutter'],
    pattern: /[A-Za-z0-9_./-]+\.kt\b/,
    hint: 'Kotlin source path (Android harness only)',
  },
  {
    id: 'maven-command',
    allowed: ['maven'],
    pattern: /(?:^|[\s`(])(?:\.\/mvnw|mvn)(?=$|[\s`).,;:!?])/,
    // Lookahead, so a closing backtick or punctuation still counts as the end
    // of the token: "run `./mvnw` first" is an instruction, not prose.

    hint: 'Maven command',
  },
];

const MAP_FILE = join(ROOT, 'AGENTS.md');
const DOCS_DIR = join(ROOT, 'docs');
const DOC_FILES = new Set(['README.md', 'AGENTS.md']);

/** Directories intentionally exempt from R3. */
const EXEMPT_FROM_TOKENS = new Set([DOCS_DIR]);

/** CI workflows, and the marker declaring a project deliberately outside CI. */
const WORKFLOWS_DIR = join(ROOT, '.github', 'workflows');
const CI_EXEMPT_MARKER = 'boundary-allow-no-ci:';

/**
 * R4/R5 vocabulary. A command is a verification gate when one of its words is
 * in this set, or when it runs a checked-in harness (`harnessInvocation`).
 */
const VERIFICATION_KEYWORDS = new Set([
  'test', 'tests', 'check', 'checks', 'lint', 'format', 'analyze', 'analyse',
  'verify', 'smoke', 'guard', 'guards', 'boundaries', 'typecheck', 'pytest',
]);

/**
 * Executables a documented line has to start with to be read as a command at
 * all. Without this, sample payloads and prose inside code fences are tokenised
 * and matched like commands.
 */
const EXECUTABLES = new Set([
  'bash', 'sh', 'zsh', 'node', 'npm', 'npx', 'pnpm', 'yarn', 'python',
  'python3', 'pytest', 'pip', 'pip3', 'alembic', 'uvicorn', 'mvn', 'mvnw',
  'gradle', 'gradlew', 'java', 'flutter', 'dart', 'go', 'cargo', 'make',
  'docker', 'git', 'ruby', 'php', 'dotnet',
]);

/** Walk a tree, skipping ignored directories. `visit(fullPath, name)`. */
function walk(dir, visit, skip = new Set(), depth = 0) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    if (entry.isSymbolicLink()) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (IGNORED_DIRS.has(entry.name) || (entry.name.startsWith('.') && entry.name !== '.')) continue;
      if (skip.has(full)) continue;
      if (depth > 12) continue;
      walk(full, visit, skip, depth + 1);
    } else if (entry.isFile()) {
      visit(full, entry.name);
    }
  }
}

function findSourceExtension(dir, skip = new Set()) {
  const counts = new Map();
  walk(dir, (full, name) => {
    const dot = name.lastIndexOf('.');
    if (dot <= 0) return;
    const ext = name.slice(dot);
    if (SOURCE_EXTENSIONS.has(ext)) counts.set(ext, (counts.get(ext) ?? 0) + 1);
  }, skip);
  return counts;
}

function markerKind(dir) {
  for (const [marker, kind] of KIND_MARKERS) {
    if (existsSync(join(dir, marker))) return kind;
  }
  return null;
}

function detectKind(dir) {
  const marker = markerKind(dir);
  if (marker) return marker;
  const counts = findSourceExtension(dir);
  let best = null;
  for (const [ext, count] of counts) {
    const kind = EXTENSION_KINDS.get(ext);
    if (!kind) continue;
    if (!best || count > best.count) best = { kind, count };
  }
  return best?.kind ?? 'unknown';
}

/** Directories holding an AGENTS.md anywhere in the tree. */
function findGuidanceDirs(root) {
  const dirs = new Set();
  walk(root, (full, name) => {
    if (name === 'AGENTS.md') dirs.add(dirname(full));
  });
  return dirs;
}

/**
 * Scopes to check: the repository root, each top-level project (a directory
 * with a marker file or program sources), and each directory that carries an
 * AGENTS.md of its own.
 */
function discoverScopes() {
  const scopeDirs = new Map(); // absolute dir -> { name, kind }
  const guidanceDirs = findGuidanceDirs(ROOT);
  scopeDirs.set(ROOT, { name: '.', kind: detectKind(ROOT) });

  // A nested scope's sources belong to that nested project, so a directory
  // that only groups other projects (vendor/, holding vendor/github setup/)
  // is not itself a project and needs no guidance of its own.
  for (const entry of readdirSync(ROOT, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (IGNORED_DIRS.has(entry.name) || TOOLING_DIRS.has(entry.name) || (entry.name.startsWith('.') && entry.name !== '.')) continue;
    const dir = join(ROOT, entry.name);
    const nested = new Set([...guidanceDirs].filter((d) => d !== dir));
    const hasOwnSources = findSourceExtension(dir, nested).size > 0;
    const isProject = markerKind(dir) !== null || hasOwnSources || guidanceDirs.has(dir);
    if (isProject) scopeDirs.set(dir, { name: entry.name, kind: detectKind(dir) });
  }

  for (const dir of guidanceDirs) {
    if (scopeDirs.has(dir)) continue;
    const name = (relative(ROOT, dir) || '.').replace(/\\/g, '/');
    scopeDirs.set(dir, { name, kind: detectKind(dir) });
  }

  return scopeDirs;
}

/** R1: guidance exists inside this scope, not merely in a nested scope. */
function hasOwnGuidance(scopeDir, allScopeDirs) {
  const nested = new Set([...allScopeDirs].filter((dir) => dir !== scopeDir));
  let found = false;
  walk(scopeDir, (full, name) => {
    if (name === 'AGENTS.md') found = true;
  }, nested);
  return found;
}

/** R2: the root map names this path (raw, percent-decoded, or as a link). */
function mapMentions(mapText, scopeName) {
  const normScope = scopeName.replace(/\\/g, '/');
  if (normScope === '.') return true;
  const decoded = mapText.replace(/%20/g, ' ');
  const needles = [
    `${normScope}/`, `${normScope}\``, `${normScope})`, `${normScope} `,
    `${normScope}*`, `${normScope}.`,
  ];
  return needles.some((needle) => decoded.includes(needle));
}

/** R3: scan a scope's own README/AGENTS files. */
function scanDocs(scopeDir, kind, allScopeDirs) {
  const violations = [];
  const escapes = [];
  const nested = new Set([...allScopeDirs].filter((dir) => dir !== scopeDir));
  if (EXEMPT_FROM_TOKENS.has(scopeDir)) return { violations, escapes };

  walk(scopeDir, (full, name) => {
    if (!DOC_FILES.has(name)) return;
    if (full === MAP_FILE) return; // the map names every project on purpose
    const lines = readFileSync(full, 'utf8').split(/\r?\n/);
    lines.forEach((line, index) => {
      for (const rule of TOKEN_RULES) {
        if (rule.allowed.includes(kind)) continue;
        const match = line.match(rule.pattern);
        if (!match) continue;
        if (line.includes('boundary-allow')) {
          const reason = (line.split('boundary-allow:')[1] ?? '')
            .replace(/-->|\*\/|--!>/, '')
            .trim() || '(no reason given)';
          escapes.push({ file: full, line: index + 1, token: match[0].trim(), reason });
          continue;
        }
        violations.push({
          file: full,
          line: index + 1,
          // Some patterns capture a leading delimiter (the backtick or space
          // that introduced the token); it is not part of the command.
          token: match[0].replace(/^[\s`(]+/, '').trim(),
          hint: rule.hint,
          kind,
          allowed: rule.allowed.join(', '),
        });
      }
    });
  }, nested);
  return { violations, escapes };
}

/**
 * Split a line of shell into the commands it actually runs. A documented
 * `cd X && python -m pytest -q` has to yield the pytest part, and a CI step
 * that chains two gates has to yield both.
 */
function splitCommands(text) {
  return text.split(/&&|;/).map((part) => part.trim()).filter(Boolean);
}

/** Tokens of a command, stripped of quoting and leading VAR=value prefixes. */
function commandTokens(command) {
  return command
    .split(/\s+/)
    .map((token) => token.replace(/^[(`'"[\]]+/, '').replace(/[)`,;:'"[\]]+$/, ''))
    .filter(Boolean)
    .filter((token) => !/^[A-Za-z_][A-Za-z0-9_]*=/.test(token));
}

/** True when a line starts like a command rather than like prose or JSON. */
function looksLikeCommand(command) {
  const first = commandTokens(command)[0] ?? '';
  if (!first) return false;
  if (first.startsWith('./') || first.startsWith('../')) return true;
  return EXECUTABLES.has(first.split('/').pop());
}

/** True when a token names a checked-in gate script or test harness. */
function isHarnessPath(token) {
  if (!/\.(?:sh|bash|ps1|mjs|cjs|js|py)$/.test(token)) return false;
  if (/(?:^|\/)(?:scripts|tools|bin|tool|test|tests|spec|specs)\//.test(token)) return true;
  return /(?:\.test\.|_test\.|\.spec\.)/.test(token);
}

/**
 * The checked-in harness a command runs, if any.
 *
 * A bare `tests/test_x.py` is a file reference in prose rather than a command,
 * so a harness path only counts when something invokes it
 * (`python scripts/x.py`), or when it is an explicitly executable path
 * (`./scripts/x.sh`).
 */
function harnessInvocation(command) {
  const tokens = commandTokens(command);
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!isHarnessPath(token)) continue;
    if (index > 0) return token;
    if (token.startsWith('./') || token.startsWith('../')) return token;
    if (/\.(?:sh|bash|ps1)$/.test(token)) return token;
  }
  return null;
}

/** True when a line runs a verification gate, as opposed to mentioning one. */
function isGateCommand(command) {
  const tokens = commandTokens(command);
  if (tokens.length === 0) return false;
  if (tokens.some((token) => VERIFICATION_KEYWORDS.has(token.toLowerCase()))) {
    return looksLikeCommand(command);
  }
  return harnessInvocation(command) !== null;
}

/**
 * The comparable form of a command: the harness it runs, or its runner plus
 * subcommand. Flags and arguments are dropped, so a documented
 * `python -m pytest -q` matches CI's `python -m pytest --quiet`, and any
 * invocation of a harness matches whatever arguments CI passes it.
 */
function commandSignature(command) {
  const harness = harnessInvocation(command);
  // A leading './' is how the doc and the workflow write the same path.
  if (harness !== null) return [harness.replace(/^\.\//, '')];
  const tokens = commandTokens(command).filter(
    (token) => !token.startsWith('-') && !token.includes('${{'),
  );
  return tokens.slice(0, 2);
}

/** True when every token of `signature` appears in `against`, in order. */
function signatureRuns(signature, against) {
  if (signature.length === 0 || against.length === 0) return false;
  let cursor = 0;
  for (const token of signature) {
    const found = against.indexOf(token, cursor);
    if (found === -1) return false;
    cursor = found + 1;
  }
  return true;
}

/** Verification gates documented in one file, with their line numbers. */
function documentedGateCommands(file) {
  const found = [];
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  let inFence = false;
  lines.forEach((line, index) => {
    if (/^\s*(?:```|~~~)/.test(line)) {
      inFence = !inFence;
      return;
    }
    const candidates = [];
    if (inFence) {
      // Inside a fence every line is literal shell. A leading '#' is a comment
      // line (skip it — stripping the '#' would turn prose into a command), and
      // a trailing one explains the command rather than being part of it.
      if (!/^\s*#/.test(line)) {
        const shell = line.replace(/^\s*\$\s*/, '').replace(/\s+#.*$/, '').trim();
        if (shell) candidates.push(shell);
      }
    } else {
      for (const match of line.matchAll(/`([^`\n]+)`/g)) candidates.push(match[1].trim());
    }
    for (const candidate of candidates) {
      for (const part of splitCommands(candidate)) {
        if (!isGateCommand(part)) continue;
        // A line-level `boundary-allow: <reason>` exempts this command too: some
        // gates run inside a suite rather than as a workflow step of their own.
        const annotated = line.includes('boundary-allow') && !line.includes(CI_EXEMPT_MARKER);
        found.push({
          file,
          line: index + 1,
          command: part,
          reason: annotated
            ? (line.split('boundary-allow:')[1] ?? '')
                .replace(/-->|\*\/|--!>/, '').trim() || '(no reason given)'
            : null,
        });
      }
    }
  });
  return found;
}

/** Every gate command run by the repository's workflows, with line numbers. */
function workflowGateCommands() {
  const found = [];
  if (!existsSync(WORKFLOWS_DIR)) return found;
  for (const entry of readdirSync(WORKFLOWS_DIR, { withFileTypes: true })) {
    if (!entry.isFile() || !/\.ya?ml$/i.test(entry.name)) continue;
    const file = join(WORKFLOWS_DIR, entry.name);
    const lines = readFileSync(file, 'utf8').split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 1) {
      const start = lines[index].match(/^(\s*)(?:-\s*)?run:\s*(.*)$/);
      if (!start) continue;
      const [, indent, inline] = start;
      let block = [{ line: index + 1, text: inline.trim() }];
      if (/^[|>][-+]?\s*$/.test(inline.trim())) {
        // A block scalar: every more-indented line below belongs to this step.
        block = [];
        for (let scan = index + 1; scan < lines.length; scan += 1) {
          const line = lines[scan];
          if (line.trim() && line.match(/^\s*/)[0].length <= indent.length) break;
          block.push({ line: scan + 1, text: line.trim() });
          index = scan;
        }
      }
      for (const piece of block) {
        for (const part of splitCommands(piece.text)) {
          if (isGateCommand(part)) found.push({ file, line: piece.line, command: part });
        }
      }
    }
  }
  return found;
}

/**
 * R4/R5: every gate command this scope's own guidance documents.
 *
 * Nested project scopes are skipped, so a finding is always attributable to the
 * guidance that owns it.
 */
function documentedInScope(scopeDir, allScopeDirs, exempt) {
  const found = [];
  const nested = new Set([...allScopeDirs].filter((dir) => dir !== scopeDir));
  walk(scopeDir, (full, name) => {
    if (!DOC_FILES.has(name)) return;
    for (const entry of documentedGateCommands(full)) {
      found.push({ ...entry, scope: (relative(ROOT, dirname(full)) || '.').replace(/\\/g, '/'), exempt });
    }
  }, nested);
  return found;
}

/** The reason this scope is declared outside CI coverage, if it is. */
function ciExemptionReason(mapText, scopeName) {
  const normScope = scopeName.replace(/\\/g, '/');
  if (normScope === '.') return null;
  const decoded = mapText.replace(/%20/g, ' ');
  for (const line of decoded.split(/\r?\n/)) {
    if (!mapMentions(line, normScope)) continue;
    const index = line.indexOf(CI_EXEMPT_MARKER);
    if (index === -1) continue;
    return line.slice(index + CI_EXEMPT_MARKER.length).trim() || '(no reason given)';
  }
  return null;
}

function main() {
  if (!existsSync(MAP_FILE)) {
    console.error(`✗ root AGENTS.md is missing at ${relative(process.cwd(), MAP_FILE) || 'AGENTS.md'}`);
    process.exit(1);
  }

  const mapText = readFileSync(MAP_FILE, 'utf8');
  const scopes = discoverScopes();
  const scopeDirs = new Set(scopes.keys());
  const failures = [];
  const seen = new Set();
  const escapes = [];
  const documented = [];
  const exemptions = [];
  let docCount = 0;

  const fail = (message) => {
    if (seen.has(message)) return;
    seen.add(message);
    failures.push(message);
  };

  for (const [dir, { name, kind }] of scopes) {
    if (!hasOwnGuidance(dir, scopeDirs)) {
      fail(
        `R1 ${name === '.' ? '.' : `${name}/`} has no AGENTS.md — add guidance ` +
        'for this project (and an entry in the root project map).',
      );
    }
    if (!mapMentions(mapText, name)) {
      fail(
        `R2 ${name}/ is a project tree but is not named in AGENTS.md's project ` +
        'map — map it so the next reader knows which rules apply.',
      );
    }
    const { violations, escapes: found } = scanDocs(dir, kind, scopeDirs);
    escapes.push(...found);
    for (const v of violations) {
      fail(
        `R3 ${relative(ROOT, v.file)}:${v.line} — ${v.hint} \`${v.token}\` ` +
        `belongs to ${v.allowed} projects, but this tree is ${v.kind}. ` +
        'Move it to its own project or annotate the line with ' +
        '`boundary-allow: <reason>`.',
      );
    }

    const exemption = ciExemptionReason(mapText, name);
    if (exemption !== null) exemptions.push({ name, reason: exemption });
    documented.push(...documentedInScope(dir, scopeDirs, exemption !== null));
  }

  // R4/R5: the documented gates and the CI gates have to be the same set.
  const ciGates = workflowGateCommands();

  for (const entry of documented) {
    if (entry.exempt) continue;
    if (entry.reason !== null) {
      escapes.push({
        file: entry.file,
        line: entry.line,
        token: entry.command,
        reason: entry.reason,
      });
      continue;
    }
    const signature = commandSignature(entry.command);
    if (ciGates.some((gate) => signatureRuns(signature, commandSignature(gate.command)))) continue;
    fail(
      `R4 ${relative(ROOT, entry.file)}:${entry.line} documents \`${entry.command}\`, ` +
      'but no CI workflow runs it — wire it into .github/workflows, or declare the ' +
      `project out of CI coverage with \`${CI_EXEMPT_MARKER} <reason>\` in the root map.`,
    );
  }

  for (const gate of ciGates) {
    const signature = commandSignature(gate.command);
    if (documented.some((entry) => signatureRuns(signature, commandSignature(entry.command)))) continue;
    fail(
      `R5 ${relative(ROOT, gate.file)}:${gate.line} runs \`${gate.command}\`, but no ` +
      "project's guidance documents it — write the gate down (and how to run it " +
      'locally), or drop the step.',
    );
  }

  walk(ROOT, (full, name) => {
    if (DOC_FILES.has(name)) docCount += 1;
  });

  for (const escape of escapes) {
    console.log(
      `• allowed by annotation — ${relative(ROOT, escape.file)}:${escape.line} ` +
      `(${escape.reason})`,
    );
  }

  for (const exemption of exemptions) {
    console.log(`• outside CI by declaration — ${exemption.name}/ (${exemption.reason})`);
  }

  if (failures.length > 0) {
    console.error(`✗ project boundaries: ${failures.length} problem(s)`);
    for (const failure of failures) console.error(`  ${failure}`);
    console.error(
      `\n  scanned ${scopes.size} scopes, ${docCount} guidance documents, ` +
      `${documented.length} documented gate(s), ${ciGates.length} CI gate(s) ` +
      `(root: ${relative(process.cwd(), ROOT) || '.'})`,
    );
    process.exit(1);
  }

  console.log(
    `✓ project boundaries: ${scopes.size} scopes, ${docCount} guidance documents, ` +
    `no missing guidance, no cross-scope instructions, and every one of the ` +
    `${documented.length} documented gates matches the ${ciGates.length} CI gates`,
  );
}

main();
