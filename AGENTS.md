# Repository agent guidance

## Project map

- The root Maven project is the Spring Boot service and shared Java liveness engine. Java source and test rules are in [`src/AGENTS.md`](src/AGENTS.md).
- [`pad_liveness_backend/`](pad_liveness_backend/) is the independent FastAPI/PostgreSQL backend; follow its [`AGENTS.md`](pad_liveness_backend/AGENTS.md).
- [`android_client/`](android_client/) contains a pinned Flutter/Android build harness and MOSIP host-integration fragments, not the full Registration Client; follow its [`AGENTS.md`](android_client/AGENTS.md).
- [`docs/`](docs/) contains repository and integration documentation; follow [`docs/AGENTS.md`](docs/AGENTS.md).
- [`training/`](training/) holds the synthetic-data model training pipeline (Python/TensorFlow); follow its [`AGENTS.md`](training/AGENTS.md). Its outputs stay local and are not wired into the service or covered by CI. boundary-allow-no-ci: no tests, and it runs on a workstation with its own TensorFlow environment

## Rules that apply across this repository

- Apply the closest `AGENTS.md`; nested project rules add detail for their own files. Do not carry one project's build assumptions or conventions into another.
- Keep changes focused on the requested work. Inspect existing edits before changing a file, and preserve unrelated work in the shared checkout.
- Keep credentials, private keys, real biometric data, and production identifiers out of source, tests, logs, and documentation.
- When a change crosses a project boundary, keep the affected API or integration contract and its documentation in sync.
- Run the checks documented for the project you changed. Report checks that could not run and why; do not describe an unrun check as passing.

## Verification gates outside Maven

These checked-in scripts are the *Secret scan*, *Package*, *Linux packages*, *Windows launcher*, *Windows installer*, *Docker image* and *Cold boot time* CI jobs. Run them from the repository root; they are not part of the Maven lifecycle because a green Java suite says nothing about packaging, boot time, Docker or the Windows launcher.

The one exception is the Linux installers: the root pom's `linux-package` profile calls `scripts/package-linux.sh` from the `package` phase, so `./mvnw package` on Linux writes `target/dist/*.deb` and `*.AppImage` beside the jar. That profile skips an installer whose tool is missing (a developer without `dpkg-deb` still needs the jar); the CI job adds `-Dlinux.package.strict=true` so the gate cannot pass with one artifact missing. <!-- boundary-allow: the *Linux packages* job runs it through that Maven phase, so the only CI invocation is `./mvnw ... package` -->

The Windows half has the same shape: the `windows-package` profile calls `scripts/package-windows.ps1` from the `package` phase, so `\mvnw.cmd package` on Windows writes `target\dist\*.exe`. WiX Toolset 3 is the optional piece there (jpackage shells out to `candle.exe`/`light.exe`); a missing toolchain still builds the app image, and `-Dwindows.package.strict=true` is what the *Windows installer* job passes so the gate cannot pass without the installer. <!-- boundary-allow: the *Windows installer* job runs it through that Maven phase, so the only CI invocation is `.\mvnw.cmd ... package` -->

- `./scripts/check-secrets.sh` — scans the checkout and its history for committed secrets.
- `./scripts/smoke-jar.sh` — compares the packaged jar's `BOOT-INF/lib` with the runtime classpath `pom.xml` resolves, then boots it and probes `/health`.
- `./scripts/release-assets.sh rehearse` — self-test of the release publish path: stages fixture assets, asserts the count, name and checksum guards in the direction that must fail, and prints the `gh release create`/`upload` argv for stable and canary tags with no token and no network. The same script is what the release job runs on the real artifacts; `add`/`checksums`/`attach --repo <owner/scratch>` rehearse a publish against a scratch repository, and `attach --dry-run` prints the command instead of running it.
- `./scripts/check-jar-size.sh <jar> <max-mib> <label>` — enforces the artifact size ceiling.
- `pwsh -NoProfile -File ./scripts/smoke-windows-launcher.ps1` — boots `start.bat` with a JDK under a path containing a space (Windows runner only).
- `pwsh -NoProfile -File ./scripts/smoke-windows-installer.ps1` — reads the built `.exe` as a PE image with a version resource matching its name, then boots the application image the installer deploys against `/health` and the console (Windows runner only).
- `./scripts/smoke-docker.sh` — builds the image and smoke tests the container.
- `./scripts/boot-median.sh` — measures the median of five cold boots against the budget.
- `./scripts/smoke-systemd-compat.sh` — boots the unit under the oldest systemd the project supports (`SMOKE_COMPAT_IMAGES`, default `debian:11 ubuntu:22.04`) in containers, and fails when a directive in it is one that host silently ignores: `systemctl show -p <directive>` must name the property, its value must match the unit, and the service must still start, answer `/health` with the engine available and survive a restart. The runner's own boot in *Linux packages* proves the newest systemd; this proves the floor. Containers share the host kernel, so the kernel floor (5.8, for `ProtectProc`/`ProcSubset`) is asserted as a precondition of the gate rather than booted.
- `./scripts/smoke-linux-packages.sh` — inspects the built `.deb` payload (launcher, systemd unit, `daemon-reload` hook) and the unit's sandboxing directives, then boots the AppImage against `/health` and the console page. `SMOKE_UNIT_BOOT=1` (root or passwordless sudo, free port 8000) also installs the package, starts the unit and probes it before purging it — CI sets that, because a sandbox that fails to mount only shows up at runtime. Linux only; it needs `dpkg-deb`.

- Run `node scripts/check-java-imports.mjs` after restoring, moving, renaming or deleting Java sources. It fails when an `io.mosip.liveness.*` import under `src` names no source file that declares it — the signature of a partial source-tree restore, which otherwise only surfaces as `cannot find symbol` against the wrong files. In a git checkout each failure also names the commit that removed the class and the `git show <sha>^:<path>` command that restores it.
- Run `node scripts/check-java-packages.mjs` after moving or renaming Java files. It fails when a file's declared package contradicts its directory, which Maven resolves by package rather than path, so the mismatch otherwise only surfaces as `package X does not exist` in every consumer.
- Run `node scripts/check-doc-links.mjs` after moving, renaming or deleting a file that documentation links to, and after editing a heading a link anchors to. It resolves every relative markdown link from the file that contains it, the way a reader's browser does — so `src/main/...` written from inside `docs/` is reported even though the file exists at the root — and checks `#heading` anchors against the target's headings, including GitHub's `-1`/`-2` suffixes for repeated headings. External `http(s)` URLs are deliberately not fetched, so a transient 503 cannot fail a documentation change. It runs in CI as the *Documentation links* job.
- Run `node scripts/check-project-boundaries.mjs` after adding a project, editing guidance or a README, or changing a CI workflow. It checks that every project tree has guidance and a map entry, that no project's documents carry another project's toolchain, that every verification command a README or AGENTS file tells you to run is actually run by a CI workflow, and that every CI step running a gate is documented somewhere. It runs in CI as the *Project boundaries* job.
- A project that is deliberately outside CI coverage declares it on its bullet above with `boundary-allow-no-ci: <reason>`, so a missing CI job stays a stated decision instead of looking like an oversight.
