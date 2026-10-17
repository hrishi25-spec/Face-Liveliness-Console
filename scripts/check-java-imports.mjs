#!/usr/bin/env node
/**
 * Java import-resolution gate.
 *
 * A partial restore of the source tree is a failure mode this repository has
 * already shipped once: commit 0824f02 deleted `src/`, and the later restore
 * brought back most of it but left thirteen classes behind. Every one of them
 * was still imported by files that stayed, so `./mvnw compile` failed with
 * pages of `cannot find symbol` for classes nobody had touched, and the
 * launcher could not start.
 *
 * Maven cannot see that failure until it compiles, and it attributes each
 * missing class to the *importing* file, which makes the real cause (a deleted
 * class) look like a broken consumer. This gate reads imports instead of
 * compiling: every `io.mosip.liveness.*` import must name a `.java` file under
 * `src/main/java` or `src/test/java` that actually declares it, so the report
 * points at the class that went missing.
 *
 * In a git checkout each failure also carries the history that explains it:
 * the commit that deleted the class (with the exact `git show <sha>^:<path>`
 * command that restores it), or, when no such commit touched the expected path,
 * the last commit that touched a file of that name anywhere — the shape a move
 * or a restore into the wrong package leaves behind. Outside a git checkout the
 * hint is omitted rather than failing the gate.
 *
 * Resolution handles the three shapes the sources use:
 *   - a plain class import (`io.mosip.liveness.core.Frame`)
 *   - a nested class (`...LivenessHttpClientWireTest.LivenessClientSpy`) and a
 *     static member (`import static ...Frame.of`), by shortening the name until
 *     a file exists that declares its own type
 *   - a wildcard (`io.mosip.liveness.core.*`), by requiring the package
 *     directory to still contain at least one source file
 *
 * Imports of third-party and JDK packages are ignored: they are resolved by
 * Maven from the classpath, not from this tree.
 *
 * Usage
 *   node scripts/check-java-imports.mjs                          # exits 1 on violations
 *   JAVA_IMPORTS_ROOT=<dir> node scripts/check-java-imports.mjs  # fixtures
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(process.env.JAVA_IMPORTS_ROOT ?? join(SCRIPT_DIR, '..'));

/** The package every repository-owned class lives in. */
const REPO_PACKAGE = 'io.mosip.liveness';

/** Maven source roots, in resolution order. */
const SOURCE_ROOTS = ['src/main/java', 'src/test/java'].map((p) => join(ROOT, p));

/** Files and directories never scanned (build output, VCS, caches). */
const IGNORED_DIRS = new Set(['.git', 'target', 'build', 'out', 'node_modules', '.idea']);

/** `import [static] io.mosip.liveness...;` — trailing `;` optional for broken files. */
const IMPORT_PATTERN = /^\s*import\s+(?:static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;?/;

/** `sha|date|subject`, the one format every history lookup below asks git for. */
const GIT_FORMAT = '%h|%ad|%s';

/**
 * Whether history hints are possible at all. A tarball, a vendored copy or a
 * fixture tree answers false, and the gate then reports the bare failure it
 * always did — the annotation is a courtesy, never a requirement.
 */
const GIT_AVAILABLE = (() => {
  try {
    return (
      execFileSync('git', ['-C', ROOT, 'rev-parse', '--is-inside-work-tree'], {
        stdio: ['ignore', 'pipe', 'ignore'],
      })
        .toString()
        .trim() === 'true'
    );
  } catch {
    return false;
  }
})();

/** Runs one `git log -1` over `pathspec`; returns the commit (and any paths) or null. */
function gitLogLine(pathspec, extraArgs = []) {
  try {
    const out = execFileSync(
      'git',
      ['-C', ROOT, 'log', '-1', `--format=${GIT_FORMAT}`, '--date=short', ...extraArgs, '--', pathspec],
      { stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 1 << 20 },
    )
      .toString()
      .trim();
    if (!out) return null;
    const [header, ...rest] = out.split('\n');
    const [sha, date, ...subject] = header.split('|');
    return { sha, date, subject: subject.join('|'), paths: rest.filter(Boolean) };
  } catch {
    return null;
  }
}

function describe(commit) {
  return `${commit.sha} "${commit.subject}" (${commit.date})`;
}

/**
 * The history behind one unresolved class: the commit that deleted it at the
 * path the import implies, or the last commit to touch any file of that name.
 *
 * @returns {{path: string, commit: object, deleted: boolean}|null}
 */
function historyFor(name) {
  if (!GIT_AVAILABLE) return null;
  if (name.endsWith('.*')) {
    // A package that lost its sources: the useful history is the directory's.
    const dir = name.slice(0, -2).split('.').join('/');
    for (const root of SOURCE_ROOTS) {
      const pathspec = relative(ROOT, join(root, dir));
      const commit = gitLogLine(pathspec);
      if (commit) return { path: pathspec, commit, deleted: false, directory: true };
    }
    return null;
  }
  const segments = name.split('.');
  for (let length = segments.length; length >= 2; length -= 1) {
    const rel = `${segments.slice(0, length).join('/')}.java`;
    for (const root of SOURCE_ROOTS) {
      const pathspec = relative(ROOT, join(root, rel));
      const deletion = gitLogLine(pathspec, ['--diff-filter=D']);
      if (deletion) return { path: pathspec, commit: deletion, deleted: true };
      const touched = gitLogLine(pathspec);
      if (touched) return { path: pathspec, commit: touched, deleted: false };
    }
  }
  // Nothing at the implied path: the class may have been restored elsewhere.
  const base = `${segments[segments.length - 1]}.java`;
  const anywhere = gitLogLine(`:(glob)**/${base}`, ['--name-only']);
  if (anywhere) {
    return { path: anywhere.paths[0] ?? base, commit: anywhere, deleted: false, elsewhere: true };
  }
  return null;
}

/** One actionable line under a failure, or null when history says nothing. */
function historyHint(name) {
  const found = historyFor(name);
  if (!found) return null;
  if (found.deleted) {
    return `last removed by ${describe(found.commit)}; restore with: git show ${found.commit.sha}^:${found.path}`;
  }
  if (found.directory) {
    return `the package directory was last touched by ${describe(found.commit)}`;
  }
  if (found.elsewhere) {
    return `no file of that name at the imported path; history has ${found.path} last touched by ${describe(found.commit)}`;
  }
  return `last touched by ${describe(found.commit)}; it may have been moved or renamed since`;
}

/** Declares a top-level or nested type named `name` in this file. */
function declaresType(file, name) {
  const source = readFileSync(file, 'utf8');
  return new RegExp(
    `(?:\\b(?:class|interface|enum|record)\\s+|@interface\\s+)${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`,
  ).test(source);
}

function walkJavaFiles(dir, files = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return files; // source root missing: reported by the caller, not here
  }
  for (const entry of entries) {
    if (IGNORED_DIRS.has(entry.name)) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      walkJavaFiles(full, files);
    } else if (entry.isFile() && entry.name.endsWith('.java')) {
      files.push(full);
    }
  }
  return files;
}

/** True when the package directory exists under a source root and holds sources. */
function packageHasSources(packageName) {
  const rel = packageName.split('.').join('/');
  return SOURCE_ROOTS.some((root) => {
    const dir = join(root, rel);
    if (!existsSync(dir)) return false;
    try {
      return readdirSync(dir).some((name) => name.endsWith('.java'));
    } catch {
      return false;
    }
  });
}

/**
 * Resolves one imported name to a file that declares it, or null when the
 * name only exists in this repository as a dangling reference.
 */
function resolveImport(name) {
  const segments = name.split('.');
  for (let length = segments.length; length >= 1; length -= 1) {
    const simpleName = segments[length - 1];
    const rel = `${segments.slice(0, length).join('/')}.java`;
    for (const root of SOURCE_ROOTS) {
      const file = join(root, rel);
      if (existsSync(file) && declaresType(file, simpleName)) {
        return file;
      }
    }
  }
  return null;
}

function main() {
  const existingRoots = SOURCE_ROOTS.filter((root) => existsSync(root) && statSync(root).isDirectory());
  if (existingRoots.length === 0) {
    console.error(`✗ java imports: no source roots found under ${ROOT} (looked for src/main/java, src/test/java)`);
    return 2;
  }

  const files = existingRoots.flatMap((root) => walkJavaFiles(root));
  const failures = [];
  let repoImports = 0;
  let resolvedImports = 0;

  for (const file of files) {
    let lines;
    try {
      lines = readFileSync(file, 'utf8').split(/\r?\n/);
    } catch (error) {
      failures.push({ file, line: 0, name: `<unreadable: ${error.message}>` });
      continue;
    }
    lines.forEach((text, index) => {
      const match = IMPORT_PATTERN.exec(text);
      if (!match) return;
      const name = match[1];
      if (name !== REPO_PACKAGE && !name.startsWith(`${REPO_PACKAGE}.`)) return;

      repoImports += 1;
      const location = `${relative(ROOT, file)}:${index + 1}`;

      if (name.endsWith('.*')) {
        const packageName = name.slice(0, -2);
        if (packageHasSources(packageName)) {
          resolvedImports += 1;
        } else {
          failures.push({ location, name, why: 'package has no source files' });
        }
        return;
      }

      if (resolveImport(name)) {
        resolvedImports += 1;
      } else {
        failures.push({ location, name, why: 'no source file declares it' });
      }
    });
  }

  if (failures.length > 0) {
    console.error(`✗ java imports: ${failures.length} unresolved import(s) in ${files.length} scanned files`);
    for (const failure of failures) {
      console.error(`  ${failure.location} imports ${failure.name} — ${failure.why}`);
      const hint = historyHint(failure.name);
      if (hint) console.error(`      ${hint}`);
    }
    console.error('  Restore the missing class (or fix the import) before packaging.');
    return 1;
  }

  console.log(
    `✓ java imports: ${repoImports} repository import(s) in ${files.length} files all resolve to source`,
  );
  return 0;
}

process.exit(main());
