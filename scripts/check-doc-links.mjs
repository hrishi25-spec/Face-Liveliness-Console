#!/usr/bin/env node
/**
 * Documentation link gate.
 *
 * Every cross-link in this repository's documentation is a promise that the
 * file it names still exists. Nothing kept that promise: `docs/` describes
 * classes, migrations and scripts by path, and when a class moves or a script
 * is renamed the prose keeps pointing at the old location. GitHub renders the
 * link as a 404, the reader concludes the doc is stale, and the only thing that
 * would have caught it is someone clicking every link.
 *
 * So this gate resolves them the way a reader does:
 *
 *   - a relative link is resolved against the directory of the file that
 *     contains it, because that is what a browser does with `docs/x.md` —
 *     writing `src/main/...` from inside `docs/` points at `docs/src/main/...`
 *     and is broken even though the file exists at the repository root;
 *   - `%20`-style escapes are decoded, so a link to a path with a space works;
 *   - a link written with a trailing slash (`docs/`) must name a directory,
 *     because that is the form that renders as a directory link;
 *   - a `#fragment` on a markdown target must match a heading in that file, or
 *     in the containing file for a bare `#fragment`, using GitHub's slug rules
 *     (lowercase, punctuation dropped, spaces to hyphens, duplicate headings
 *     numbered `-1`, `-2`). On a non-markdown target any fragment is accepted:
 *     `#L42` is an anchor GitHub generates, and anything else still opens the
 *     file, so there is nothing here to verify;
 *   - a link that climbs out of the repository is broken for every reader who
 *     clones it, so it is reported even when the path happens to exist on the
 *     machine that runs the gate.
 *
 * Deliberately not checked: external `http(s)` URLs. That would make the gate
 * depend on the network and on other people's servers, so a transient 503 would
 * turn a documentation change red. Relative links are the ones this repository
 * can keep honest, and they are the ones that break when files move.
 *
 * Also not checked: a link written inside a fenced code block or an inline code
 * span. Those are examples of markdown, not links — `[text](path)` in a sample
 * payload is not a promise about this repository.
 *
 * Usage
 *   node scripts/check-doc-links.mjs                       # exits 1 on violations
 *   DOC_LINKS_ROOT=<dir> node scripts/check-doc-links.mjs  # fixtures
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, extname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(process.env.DOC_LINKS_ROOT ?? join(SCRIPT_DIR, '..'));

/** Directories that are build output, dependency trees, or not documentation. */
const IGNORED_DIRS = new Set([
  '.git', '.github', '.claude', '.claude-flow', '.freebuff', '.readme-preview',
  '.gradle', '.dart_tool', '.idea', '.venv', 'venv', 'node_modules',
  'target', 'build', 'dist', 'out', '__pycache__', 'release-assets',
  '.pytest_cache', '.ruff_cache', '.mypy_cache', '.cache', 'htmlcov',
  'coverage', '.pub-cache', '.terraform',
]);

/** Extensions read as documentation. */
const DOC_EXTENSIONS = new Set(['.md', '.markdown']);

/** Schemes that point somewhere other than this repository. */
const EXTERNAL = /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i;

/** `[text](target)` and `![alt](target)`, with one level of nested brackets. */
const INLINE_LINK =
  /!?\[(?<text>(?:[^[\]\n]|\[[^\]\n]*\])*)\]\(\s*(?<target><[^>\n]*>|[^)\s]+)(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)/g;

/** `[id]: target` reference definitions, which render as links too. */
const REFERENCE_DEFINITION = /^\s{0,3}\[[^\]\n]+\]:\s*(?<target><[^>\n]*>|\S+)/;

/** An inline code span, whose contents are an example rather than a link. */
const CODE_SPAN = /`[^`\n]*`/g;

function walkDocs(dir, files = []) {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return files;
  }
  for (const entry of entries) {
    if (entry.isSymbolicLink()) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (IGNORED_DIRS.has(entry.name)) continue;
      walkDocs(full, files);
    } else if (entry.isFile() && DOC_EXTENSIONS.has(extname(entry.name).toLowerCase())) {
      files.push(full);
    }
  }
  return files;
}

/** Character ranges covered by inline code spans on one line. */
function codeSpanRanges(line) {
  const ranges = [];
  for (const match of line.matchAll(CODE_SPAN)) {
    ranges.push([match.index, match.index + match[0].length]);
  }
  return ranges;
}

function insideAny(index, ranges) {
  return ranges.some(([start, end]) => index >= start && index < end);
}

/**
 * GitHub's heading slug: lowercase, formatting markers dropped, punctuation
 * removed, whitespace collapsed to hyphens. Unicode letters are kept, because
 * GitHub keeps them.
 */
function slugify(heading) {
  return heading
    .replace(/<[^>]*>/g, '')
    .replace(/!?\[((?:[^[\]\n]|\[[^\]\n]*\])*)\]\([^)\n]*\)/g, '$1')
    .replace(/[`*_~]/g, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');
}

/** Every anchor a markdown file offers, including GitHub's duplicate suffixes. */
function headingsOf(file) {
  const anchors = new Set();
  const seen = new Map();
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  let fence = null;
  for (const line of lines) {
    const fenceMatch = line.match(/^\s*(```+|~~~+)/);
    if (fenceMatch) {
      if (fence === null) fence = fenceMatch[1][0].repeat(3);
      else if (fenceMatch[1].startsWith(fence)) fence = null;
      continue;
    }
    if (fence !== null) continue;
    const heading = line.match(/^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$/);
    if (heading) {
      const explicit = heading[1].match(/\s*\{#([^}\s]+)\}\s*$/);
      const slug = explicit ? explicit[1] : slugify(explicit ? heading[1].replace(/\s*\{#[^}\s]+\}\s*$/, '') : heading[1]);
      if (!slug) continue;
      const count = seen.get(slug) ?? 0;
      seen.set(slug, count + 1);
      anchors.add(count === 0 ? slug : `${slug}-${count}`);
      continue;
    }
    // Raw HTML anchors are legal targets too (`<a name="x"></a>`).
    for (const match of line.matchAll(/\b(?:id|name)\s*=\s*"([^"]+)"/g)) anchors.add(match[1]);
  }
  return anchors;
}

/** Split a link target into its path and fragment, before any resolution. */
function splitTarget(target) {
  let value = target.trim();
  if (value.startsWith('<') && value.endsWith('>')) value = value.slice(1, -1).trim();
  const hash = value.indexOf('#');
  const path = hash === -1 ? value : value.slice(0, hash);
  const fragment = hash === -1 ? '' : value.slice(hash + 1);
  // A query string is not part of the file a browser opens for a static doc.
  return { path: path.split('?')[0], fragment };
}

function decode(path) {
  try {
    return decodeURIComponent(path);
  } catch {
    return path; // a stray '%' is not an escape; resolve it literally
  }
}

function exists(path) {
  // A percent-decoded path and its literal form are both plausible, so either
  // match counts: the point is that the link opens something, not how the
  // author escaped it.
  return existsSync(path) || existsSync(decode(path));
}

/** Resolve one link target from one file. Returns a problem string or null. */
function checkTarget(file, target) {
  if (EXTERNAL.test(target)) return null;
  const { path: targetPath, fragment } = splitTarget(target);
  if (!targetPath) {
    if (!fragment) return null;
    return verifyFragment(file, fragment, relative(ROOT, file).split(sep).join('/'));
  }

  const anchor = targetPath.startsWith('/')
    ? join(ROOT, targetPath)
    : resolve(dirname(file), decode(targetPath));
  const shown = relative(ROOT, anchor).split(sep).join('/') || '.';
  if (shown.startsWith('..')) {
    return `link leaves the repository: ${target}`;
  }
  if (!exists(anchor)) {
    return `no such file or directory: ${target} (resolves to ${shown})`;
  }

  // The literal path or its decoded form is the one that exists; the decoded
  // form is what a reader's browser opens.
  const opened = existsSync(anchor) ? anchor : decode(anchor);
  const isDirectory = statSync(opened).isDirectory();
  if (targetPath.endsWith('/') && !isDirectory) {
    return `link names a directory but ${shown} is a file: ${target}`;
  }
  if (!fragment) return null;
  if (isDirectory) {
    return `a directory cannot have the anchor #${fragment}`;
  }
  // GitHub generates #L10 / #L10-L20 anchors for source files itself, so only
  // markdown targets have headings to verify.
  if (!DOC_EXTENSIONS.has(extname(opened).toLowerCase())) return null;
  return verifyFragment(opened, fragment, shown);
}

function verifyFragment(file, fragment, shown) {
  if (!existsSync(file)) return `no such file: ${shown}`;
  const anchors = headingsOf(file);
  const decoded = decode(fragment).toLowerCase();
  if (anchors.has(decoded) || anchors.has(fragment)) return null;
  return `no heading matches #${fragment} in ${shown}`;
}

function main() {
  if (!existsSync(ROOT)) {
    console.error(`✗ docs links: no such root: ${ROOT}`);
    return 2;
  }

  const files = walkDocs(ROOT);
  if (files.length === 0) {
    console.error(`✗ docs links: no markdown files under ${relative(process.cwd(), ROOT) || '.'}`);
    return 2;
  }

  const problems = [];
  let relativeLinks = 0;
  let external = 0;
  let anchors = 0;

  for (const file of files) {
    const lines = readFileSync(file, 'utf8').split(/\r?\n/);
    let fence = null;

    lines.forEach((line, index) => {
      const fenceMatch = line.match(/^\s*(```+|~~~+)/);
      if (fenceMatch) {
        if (fence === null) fence = fenceMatch[1][0].repeat(3);
        else if (fenceMatch[1].startsWith(fence)) fence = null;
        return;
      }
      if (fence !== null) return;

      const spans = codeSpanRanges(line);
      const found = [...line.matchAll(INLINE_LINK)];
      const definition = line.match(REFERENCE_DEFINITION);
      if (definition) found.push({ index: definition.index, groups: { target: definition.groups.target } });

      for (const match of found) {
        if (insideAny(match.index, spans)) continue;
        const target = match.groups.target;
        if (!target) continue;
        if (EXTERNAL.test(target)) {
          external += 1;
          continue;
        }
        if (target.startsWith('#')) {
          anchors += 1;
        } else {
          relativeLinks += 1;
          if (splitTarget(target).fragment) anchors += 1;
        }
        const problem = checkTarget(file, target);
        if (problem) problems.push({ file, line: index + 1, problem, target });
      }
    });
  }

  if (problems.length > 0) {
    console.error(`✗ docs links: ${problems.length} broken link(s) in ${files.length} markdown file(s)`);
    for (const problem of problems) {
      console.error(`  ${relative(ROOT, problem.file).split(sep).join('/')}:${problem.line} — ${problem.problem}`);
    }
    console.error(
      '\n  A link is resolved from the file that contains it, the way a reader\'s browser does.',
      'From `docs/x.md`, `src/main/...` means `docs/src/main/...`; the repository-root path is `../src/main/...`.',
    );
    process.exit(1);
  }

  console.log(
    `✓ docs links: ${files.length} markdown file(s), ${relativeLinks} relative link(s) ` +
    `(${anchors} with an anchor) all resolve; ${external} external link(s) not fetched`,
  );
}

main();
