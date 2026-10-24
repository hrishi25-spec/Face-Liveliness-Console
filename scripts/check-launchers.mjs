#!/usr/bin/env node
/**
 * Developer-launcher gate.
 *
 * `start.sh`, `start.bat`, `run.sh` and `run.bat` (and the copies under
 * `scripts/`) are the first thing a new contributor runs. They build the jar
 * with `mvnw package`, and `package` also activates the OS packaging profiles:
 * on Linux that can download appimagetool, and on Windows it needs WiX. A
 * launcher that forgets `-Dlinux.package.skip=true -Dwindows.package.skip=true`
 * therefore turns a 30-second local start into an installer build, which is
 * exactly what happened before those flags were added.
 *
 * This gate keeps the invariant visible instead of relying on every future
 * edit to remember it:
 *
 *   - every launcher defines `SKIP_INSTALLERS` with both flags set to `true`;
 *   - every Maven lifecycle invocation (`package`, `install`, `verify`,
 *     `deploy`) in a launcher passes that variable or both flags inline;
 *   - no launcher invokes the packaging scripts or their tools directly; and
 *   - a launcher still contains at least one Maven build line, so the check
 *     cannot silently stop checking a file whose build moved elsewhere.
 *
 * Comments are skipped. Echoed text is checked for the Maven rule too: the
 * Windows doctor prints a "rebuild with" command, and a suggestion that omits
 * the flags is just as capable of building installers as a real invocation.
 *
 * Usage
 *   node scripts/check-launchers.mjs                          # exits 1 on violations
 *   LAUNCHER_ROOT=<dir> node scripts/check-launchers.mjs      # fixtures
 */

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(process.env.LAUNCHER_ROOT ?? join(SCRIPT_DIR, '..'));

const LINUX_FLAG = '-Dlinux.package.skip=true';
const WINDOWS_FLAG = '-Dwindows.package.skip=true';

const LAUNCHERS = [
  'start.sh',
  'start.bat',
  'run.sh',
  'run.bat',
  'scripts/start.sh',
  'scripts/start.bat',
];

/** Packaging entry points a developer launcher must never reach. */
const PACKAGING_TOKENS = [
  'package-linux.sh',
  'package-windows.ps1',
  'appimagetool',
  'jpackage',
  'candle.exe',
  'light.exe',
  'wixtoolset',
];

/** Lines that print a message rather than run a command. */
const OUTPUT_LINE = /^\s*(?:echo|printf|say|info|ok|warn|die)\b/i;

function isComment(line) {
  return /^\s*@?(?:#|rem(?:\s|$)|::)/i.test(line);
}

/** Split a line into the commands it runs, so a one-line `if`/`else` is checked per branch. */
function commandSegments(line) {
  return line.split(/\s*(?:&&|\|\||[;&])\s*/).filter((segment) => segment.trim());
}

/** True for a line that runs Maven: the wrapper, `mvn`, or the resolved variable. */
function isMavenLine(line) {
  return /\bmvnw?(?:\.cmd)?\b/i.test(line)
    || /\$MVN\b/.test(line)
    || /%MVN%/i.test(line);
}

/** True for a Maven lifecycle phase that can reach the packaging profiles. */
function hasPackagingPhase(line) {
  return /\b(?:package|install|verify|deploy)\b/i.test(line);
}

function checkLauncher(relativePath, problems) {
  const path = join(ROOT, relativePath);
  if (!existsSync(path)) {
    problems.push(`${relativePath}: launcher is missing`);
    return;
  }

  const lines = readFileSync(path, 'utf8').split(/\r?\n/);
  let definesSkipFlags = false;
  let buildLines = 0;

  lines.forEach((line, index) => {
    const where = `${relativePath}:${index + 1}`;
    if (isComment(line)) return;

    if (/\bSKIP_INSTALLERS\s*=/.test(line)) {
      definesSkipFlags = true;
      if (!line.includes(LINUX_FLAG) || !line.includes(WINDOWS_FLAG)) {
        problems.push(
          `${where}: SKIP_INSTALLERS must set both ${LINUX_FLAG} and ${WINDOWS_FLAG}`,
        );
      }
    }

    let lineHasBuild = false;
    for (const segment of commandSegments(line)) {
      if (!isMavenLine(segment) || !hasPackagingPhase(segment)) continue;
      lineHasBuild = true;
      if (!segment.includes('SKIP_INSTALLERS')
          && !(segment.includes(LINUX_FLAG) && segment.includes(WINDOWS_FLAG))) {
        problems.push(
          `${where}: Maven build must pass both installer-skip flags: ${segment.trim()}`,
        );
      }
    }
    if (lineHasBuild) buildLines += 1;

    if (!OUTPUT_LINE.test(line)) {
      for (const token of PACKAGING_TOKENS) {
        if (line.toLowerCase().includes(token)) {
          problems.push(
            `${where}: launcher must not run the installer tooling (${token}): ${line.trim()}`,
          );
        }
      }
    }
  });

  if (!definesSkipFlags) {
    problems.push(
      `${relativePath}: defines no SKIP_INSTALLERS variable with both installer-skip flags`,
    );
  }
  if (buildLines === 0) {
    problems.push(
      `${relativePath}: no Maven build line found, so this guard is not checking it`,
    );
  }
}

const problems = [];
for (const launcher of LAUNCHERS) checkLauncher(launcher, problems);

if (problems.length > 0) {
  console.error('Developer launcher check failed:');
  for (const problem of problems) console.error(`  - ${problem}`);
  console.error(
    '\nLaunchers must pass both installer-skip flags on every Maven build:\n'
    + `  ${LINUX_FLAG} ${WINDOWS_FLAG}\n`
    + 'and must never invoke the packaging scripts or their tools.',
  );
  process.exitCode = 1;
} else {
  console.log(
    `Developer launcher check passed: ${LAUNCHERS.length} launchers build only the jar.`,
  );
}
