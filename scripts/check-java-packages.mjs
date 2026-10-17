#!/usr/bin/env node
/**
 * Java package-layout gate.
 *
 * Companion to `check-java-imports.mjs`, which proves every import names a
 * source file that declares it. That check cannot see this failure: a file
 * moved into the wrong directory, or a package declaration edited to one the
 * filesystem does not support, still resolves by *file path* — so the import
 * gate stays green while `javac` fails with "package X does not exist" for
 * every consumer, and a stray class may even shadow the real one.
 *
 * The invariant here is the one Maven and javac assume without stating it: a
 * file at `src/main/java/a/b/C.java` declares `package a.b;`, and a file with
 * no package declaration sits at the source root. A file at the root of a
 * source tree may declare no package at all (the default package) — nothing in
 * this repository does, but the layout rule allows it, so the gate does too.
 *
 * Usage
 *   node scripts/check-java-packages.mjs                          # exits 1 on violations
 *   JAVA_LAYOUT_ROOT=<dir> node scripts/check-java-packages.mjs   # fixtures
 */

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(process.env.JAVA_LAYOUT_ROOT ?? join(SCRIPT_DIR, '..'));

/** Maven source roots, the same two the import gate resolves against. */
const SOURCE_ROOTS = ['src/main/java', 'src/test/java'].map((p) => join(ROOT, p));

/** Directories never scanned (build output, VCS, caches). */
const IGNORED_DIRS = new Set(['.git', 'target', 'build', 'out', 'node_modules', '.idea']);

/** A package declaration, anchored to the line so indented prose cannot match. */
const PACKAGE_PATTERN = /^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;/m;

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

/** The package a file's directory implies; '' at a source root (default package). */
function expectedPackage(sourceRoot, file) {
  const relativePath = relative(sourceRoot, dirname(file));
  if (relativePath === '' || relativePath === '.') return '';
  return relativePath.split(sep).join('.');
}

function main() {
  const existingRoots = SOURCE_ROOTS.filter((root) => existsSync(root) && statSync(root).isDirectory());
  if (existingRoots.length === 0) {
    console.error(`✗ java packages: no source roots found under ${ROOT} (looked for src/main/java, src/test/java)`);
    return 2;
  }

  const failures = [];
  let checked = 0;

  for (const sourceRoot of existingRoots) {
    for (const file of walkJavaFiles(sourceRoot)) {
      checked += 1;
      const expected = expectedPackage(sourceRoot, file);
      let source;
      try {
        source = readFileSync(file, 'utf8');
      } catch (error) {
        failures.push({ file, expected, found: `<unreadable: ${error.message}>` });
        continue;
      }
      const declared = PACKAGE_PATTERN.exec(source);
      const found = declared ? declared[1] : '';
      if (found !== expected) {
        failures.push({ file, expected, found });
      }
    }
  }

  if (failures.length > 0) {
    console.error(`✗ java packages: ${failures.length} file(s) whose declaration contradicts their directory, of ${checked} checked`);
    for (const failure of failures) {
      const shown = failure.found === '' ? '(no package declaration)' : failure.found;
      const want = failure.expected === '' ? '(default package)' : failure.expected;
      console.error(`  ${relative(ROOT, failure.file)} declares ${shown}, but its directory means ${want}`);
    }
    console.error('  Move the file to its package directory, or fix the declaration; Maven resolves by package, not by path.');
    return 1;
  }

  console.log(`✓ java packages: all ${checked} source files declare the package their directory implies`);
  return 0;
}

process.exit(main());
