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

These checked-in scripts are the *Secret scan*, *Package*, *Windows launcher*, *Docker image* and *Cold boot time* CI jobs. Run them from the repository root; they are not part of the Maven lifecycle because a green Java suite says nothing about packaging, boot time, Docker or the Windows launcher.

- `./scripts/check-secrets.sh` — scans the checkout and its history for committed secrets.
- `./scripts/smoke-jar.sh` — boots the packaged jar and probes `/health`.
- `./scripts/check-jar-size.sh <jar> <max-mib> <label>` — enforces the artifact size ceiling.
- `pwsh -NoProfile -File ./scripts/smoke-windows-launcher.ps1` — boots `start.bat` with a JDK under a path containing a space (Windows runner only).
- `./scripts/smoke-docker.sh` — builds the image and smoke tests the container.
- `./scripts/boot-median.sh` — measures the median of five cold boots against the budget.

- Run `node scripts/check-java-imports.mjs` after restoring, moving, renaming or deleting Java sources. It fails when an `io.mosip.liveness.*` import under `src` names no source file that declares it — the signature of a partial source-tree restore, which otherwise only surfaces as `cannot find symbol` against the wrong files. In a git checkout each failure also names the commit that removed the class and the `git show <sha>^:<path>` command that restores it.
- Run `node scripts/check-java-packages.mjs` after moving or renaming Java files. It fails when a file's declared package contradicts its directory, which Maven resolves by package rather than path, so the mismatch otherwise only surfaces as `package X does not exist` in every consumer.
- Run `node scripts/check-project-boundaries.mjs` after adding a project, editing guidance or a README, or changing a CI workflow. It checks that every project tree has guidance and a map entry, that no project's documents carry another project's toolchain, that every verification command a README or AGENTS file tells you to run is actually run by a CI workflow, and that every CI step running a gate is documented somewhere. It runs in CI as the *Project boundaries* job.
- A project that is deliberately outside CI coverage declares it on its bullet above with `boundary-allow-no-ci: <reason>`, so a missing CI job stays a stated decision instead of looking like an oversight.
