# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-10-17

### Fixed — a phone-screen replay is rejected at the challenge, and the console names it

A video played from a phone was reaching `PASSED`, with no screen-replay warning anywhere. The
passive stage *did* see it — MiniFASNet scored the phone screen at ~0.99 for replay — but the
confirmed hit escalated to the active challenge, and the challenge only ran the cheap OpenCV
heuristic. The heuristic was a placeholder (`freqRatio = 0.1`) and could never fire, so the video
performed the blink and left verified. The escalation response also hard-coded `padFlag(false)`,
which is why the console had nothing to show.

- **The challenge stage runs the model too.** Challenge frames now go through the same PAD
  fusion as the passive stage (FFT/texture heuristics **or** MiniFASNet). A replay that was
  flagged before the challenge is flagged again during it, and the active stage rejects with
  `presentation_attack:<TYPE>` instead of passing. Two frames of the burst must agree
  (`CHALLENGE_PAD_CONFIRM_FRAMES`), mirroring the passive stage's confirmation rule, so a single
  model flip on a live face does not fail the person at the last stage.
- **The verdict follows the escalation.** `escalate_to_active` now carries `padFlag` and
  `padAttackType` when PAD caused it, and a PAD rejection from `challenges/validate` carries the
  same two fields. The console's warning panel therefore lists `SCREEN_REPLAY on the capture
  stage` and the rejection that follows, instead of a clean frame and a generic failure.
- **A print stays a print when both sources flag.** MiniFASNet's print class does not fire in
  practice — it measures ≈0.0000 on the print fixture *and* on a phone-screen replay, labelling
  both through its replay class — so the model alone cannot tell the two apart. The texture rule
  is the print evidence: a flat presentation keeps `PRINTED_PHOTO` even when the model calls it
  replay, and the model names the replay it can see that the texture rule cannot. The attack
  decision is unchanged either way.
- **A flat frame is not automatically paper.** The texture rule calls any low-variance frame a
  print, and a defocused phone screen is exactly that — a blurry replay was being reported as
  `PRINTED_PHOTO`. `PadEngineService` now looks for the screen's body before it says print: a
  large near-black rectangular blob, kept off the frame edge, in a brighter room, whose inner band
  is dark and smooth and whose centre is a brighter panel. That survives defocus by design (the
  pass downscales to 480 px and blurs first), and a printed sheet has no such body, so a
  defocused replay is `SCREEN_REPLAY` and a print stays `PRINTED_PHOTO`. The cue only chooses
  between the two labels for a frame already flagged as an attack; it never turns an attack into a
  pass. `PadEngineServiceScreenReplayTest` pins both sides — a blurred phone composite with a bezel
  reads `SCREEN_REPLAY`, the print fixture has zero bezel evidence and stays `PRINTED_PHOTO` — and
  the composite goes through a JPEG round-trip first, because at the working size the bezel is a
  few pixels wide and the encoder's smoothing is part of the frame the endpoint actually receives.
- **The screen-replay band is real, not a placeholder.** `PadEngineService` measures the share of
  the non-DC spectrum in the outer half of a 512×512 plane (`DFT_COMPLEX_OUTPUT` on an
  `INTER_AREA` downscale) and calls it a screen replay above `0.45`. A live face measures ~0.25 and
  a fine screen grid ~0.61; a photographed phone screen measures ~0.21, so this is a cheap backstop
  for a visible grid, **not** the detector a real replay relies on — the model is. The threshold
  stays above the live-face value so it cannot fire on one.
- **Both are pinned end to end.** `LivenessPipelineIntegrationTest` drives a synthetic 1280×720
  phone-screen frame (face on a dark-bezelled screen) through the real model and asserts the
  escalation names `SCREEN_REPLAY` and the challenge validation rejects it; `console-guards`
  asserts the warning panel and the log entries on both stages.

### Fixed — an honest face is no longer rejected by the passive PAD stage

A real face on a real webcam could be terminally rejected during the passive stage. Three
independent rules fed that outcome: MiniFASNet called any attack class that merely *outranked*
live an attack, so a 0.36 / 0.34 / 0.30 softmax was a spoof at confidence 0.36; the OpenCV
heuristic called any frame darker than 0.15 a video replay, which is exactly what a webcam still
auto-exposing from black produces; and two consecutive frames were enough to confirm both, so one
capture condition confirmed itself and ended the session before the active challenge could run.

- **A near-tie is not a verdict.** `assessPad` now requires the winning attack class to clear
  `PAD_ATTACK_MIN_CONFIDENCE` (0.60) and beat the live class by `PAD_ATTACK_MARGIN` (0.15). A
  genuine attack puts its class far above live; a three-way tie on a moving face is ranked noise.
- **Darkness is a capture problem, not attack evidence.** The brightness → `VIDEO_REPLAY` rule is
  gone. An under-exposed frame is answered by asking for another frame, not by failing the person.
- **Three frames, not two.** `PAD_CONFIRM_FRAMES` is 3, so a single exposure or head position can
  no longer confirm its own verdict.
- **A confirmed hit hands over to the challenge.** When the workflow has an active stage, a
  confirmed PAD hit escalates to it (`PAD_FLAGGED` audit) instead of failing the session; PAD keeps
  running on every challenge frame, where a hit remains terminal. Only a workflow with no
  challenge to run rejects at the passive gate (`PAD_REJECTED`).

### Changed — the capture guide fills more of the preview

The oval was a fixed 212×286 px, small on a desktop preview and larger than a short mobile one.
It is now `80%` of the viewfinder's height, capped at `430px`, on the same 212:286 ellipse, with
clearance for the pinned guidance pill; the scan sweep follows the taller guide.

### Fixed — a restarted service can no longer be hidden behind a cached console

The console's three files were served with a 10-minute freshness window and no validator, so a browser that had loaded the page before an upgrade kept running the old markup and script against the new jar. That is not a cosmetic staleness: after the sign-in dialog gained its role options, an unreloaded tab still showed the supervisor-only form, and the credentials typed into it could only re-authorise a policy — which reads as "sign in is broken" while the server was answering correctly.

- **No freshness window, in any profile.** `spring.web.resources.cache.cachecontrol` is now `max-age=0, no-cache, must-revalidate` (the wire header is `no-cache, must-revalidate, public`; a zero max-age is implied), so every load asks the process that is actually running. The dev profile keeps its stronger `no-store`. The cost is the 51 KB compressed the console weighs (§11 of `docs/performance-baseline.md`), against a page that could otherwise disagree with the service for ten minutes after every restart.
- **The trade-off is recorded where it was made.** The performance report's cache bullet now says the window was removed and why, instead of describing a policy the service no longer has.

### Added — the unit is booted on the oldest systemd it claims to support

The sandbox was only ever exercised on the CI runner's own systemd (the newest LTS). That is the wrong host to prove it on: the failure mode for an older one is silence. systemd logs a single `Unknown lvalue 'ProtectProc' in section 'Service'` at load time and then runs the unit **without** that directive — no command fails, `systemd-analyze verify` is satisfied, `/health` answers, and the hardening the unit advertises is simply absent. A kernel that cannot enforce a directive behaves the same way.

- **The unit is booted where it is weakest.** `scripts/smoke-systemd-compat.sh` starts a distribution's systemd as PID 1 in a container, installs `packaging/linux/pad-liveness.service` unmodified, and asks systemd itself what it applied. `systemctl show -p <directive>` has to name the property and report the declared value; an unknown directive prints nothing at all, which is exactly the silent drop. The boot journal is cross-checked for `Unknown lvalue`/`Unknown key` too.
- **A host that ignores a directive fails the gate, and the failure names it.** On Ubuntu 20.04's systemd 245 it reports `ignores ProtectClock=yes`, `ignores ProtectProc=invisible`, `ignores ProcSubset=pid` and exits 1; a mistyped directive is caught on any host by the same check. That is what fixes the floor at **systemd 247**, now stated in the README: 247 is where `ProtectProc`/`ProcSubset` arrive.
- **The floor host also has to run the service.** The gate starts the unit and requires `/health` with `engine: available`, the HTML console, `DynamicUser` with its `StateDirectory`, and a healthy restart — a sandbox that mounts on the new systemd but starves the JVM on the old one is a failure, not a pass. Ubuntu 22.04 (systemd 249) and Ubuntu 24.04 (255) pass all 28 directives; the distribution's own Java 17 runs the jar, so the result does not depend on the host's JDK matching an older glibc.
- **The kernel is a stated boundary, not a claim.** Containers share the host kernel, so the gate asserts the kernel floor (5.8, the version `ProtectProc`/`ProcSubset` need) as a precondition and prints the kernel it ran on. Booting an older kernel would need a VM, which the runners cannot do.
- **It runs in the *Linux packages* job**, after the native unit boot, against the same jar and with no extra setup beyond Docker.

### Added — documentation cross-links are checked, not trusted

`docs/` and the READMEs describe classes, migrations and scripts by relative path, and nothing resolved those paths. A file that moved left the link pointing at nothing while the prose still called it the source of truth — and a reader who finds one 404 has no reason to trust the rest of the page. The gate found fourteen already in `docs/api-documentation-review.md`, every one of them written as if the file sat at the repository root.

- **Links resolve the way a reader's browser resolves them.** `scripts/check-doc-links.mjs` resolves a relative link against the directory of the file that contains it, so `src/main/...` written from inside `docs/` is reported even though that path exists at the root. `%20`-escaped paths are decoded, a link written with a trailing slash must name a directory, and a link that climbs out of the repository is reported even where the path happens to exist locally.
- **Anchors are verified against real headings.** A `#fragment` on a markdown target must match a heading in that file, using GitHub's slug rules — lowercase, punctuation dropped, spaces to hyphens, and repeated headings numbered `-1`, `-2` — so retitling a section cannot silently break the table of contents that links to it. GitHub's own `#L42`/`#L42-L58` line anchors on source files are accepted as generated.
- **Examples are not promises.** Links inside fenced code blocks and inline code spans are skipped, because `[text](path)` in a sample payload says nothing about this repository. External `http(s)` URLs are deliberately not fetched: a transient 503 from someone else's server must not turn a documentation edit red.
- **It runs in CI as the *Documentation links* job**, needs only `node`, and walks the whole tree — including the sibling projects' READMEs — with no Maven, no network and nothing to install.

### Added — the release publish path is rehearsable without a tag

Publishing was the one step with no undo, and it was reachable only by pushing a tag at the real repository: three inline `run:` blocks staged the assets, wrote the checksums and called `gh`. A mistake in any of them surfaced after the tag was already public, in the one job where a re-run means republishing under a moved tag.

- **One implementation, two entry points.** `scripts/release-assets.sh` now owns `add` (stage the four binaries under their release names, refusing a name for another version or a second copy of one), `checksums` (exactly two jars, one `.deb` and one AppImage, each with a verified `.sha256`) and `attach` (exactly eight assets, the stamped jar names, create-vs-upload, and the canary prerelease flag). The release job calls it, so the published artifact is unchanged.
- **`--repo <owner/scratch>` makes it testable without a tag.** The same `attach` creates a real release in a scratch repository, every `gh` call carrying `--repo`; `--dry-run` prints the exact argv and stops, and `--assume new|existing` picks which branch is printed without contacting GitHub.
- **`rehearse` is the self-test, and CI runs it.** It stages fixture assets, checks the checksums, asserts the `gh release create` / `upload --clobber` / `--prerelease` argv, then feeds every guard the input it must reject: a jar for another version, a duplicated name, a missing asset, a short staging directory, and a jar stamped with the wrong version. The *Linux packages* job runs it on every push, so the publish path cannot rot between tags.
- **Documented where the gates are listed.** README, `AGENTS.md` and the PRD's gate table describe the rehearsal and the scratch-repository recipe.

### Added — the jar smoke test checks what the jar actually ships

The suite runs against `target/classes` with the full `~/.m2` classpath, and `DependencyOwnershipGateTest` reads the repackager's *configuration*. Nothing compared `BOOT-INF/lib` with the pom, so a repackage that dropped a jar — or shipped one the pom never declared — was invisible until the first request touched the missing class, or forever if nothing did.

- **Both directions, against the artifact.** `scripts/smoke-jar.sh` resolves the runtime classpath from `pom.xml` (`dependency:build-classpath`) and compares it with the jar's `BOOT-INF/lib` by file name. A missing entry names the dependency whose `<exclude>` (or the plugin) dropped it; an undeclared entry names the leaked jar — the direction that catches a provided dependency hiding in a release, a test artefact, or something left over from an earlier build. A version drift appears as one missing name plus one unexpected one, which is how the `junit-jupiter` split would have surfaced here.
- **The repackager's rules are reproduced, not assumed.** Boot 4's starter jars are manifest-only and are not shipped, so jars with no class files and no `META-INF/resources` are not required — a webjar such as swagger-ui lives entirely under `META-INF/resources` and still counts. `spring-boot-jarmode-tools` is injected by the plugin rather than declared, so it is excluded from the comparison. Both rules are explained where they are applied.
- **Slim is held to its actual promise.** The classpath comparison proves the opencv/onnxruntime jars are gone; the script also asserts the OpenCV bindings and the linux/x86_64 natives landed under `BOOT-INF/classes`, and that the osx, windows, ARM and x86_32 entries the profile excludes are absent. Before this, a filter that quietly stopped matching shipped ~250 MB of unusable natives and still booted green.
- **No source tree, no comparison.** Run against a jar downloaded from a release, the script warns and boots the jar; `SMOKE_CLASSPATH_FILE` supplies a precomputed classpath and `SMOKE_REFRESH_CLASSPATH=1` forces a re-resolve instead of reusing the cached one.
- **Proved to fail.** Removing `postgresql` from a jar, adding `javafx-base`, swapping `postgresql-42.7.13` for `42.6.0`, dropping `org/opencv/core/Mat.class` from the slim jar and adding a Windows `.dll` back each failed with the entry named, before the boot began.

### Changed — the packaged systemd unit runs sandboxed

`pad-liveness.service` started the service as a `DynamicUser` on the public network without dropping anything else, so a bug in frame decoding had the host's filesystem, the kernel interfaces and every other process within reach. The unit now carries the directives systemd offers for a JVM service that needs one socket and one writable directory.

- **What it drops.** `NoNewPrivileges=yes`, empty `CapabilityBoundingSet`/`AmbientCapabilities`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `PrivateDevices`, `ProtectKernel{Tunables,Modules,Logs}`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname`, `ProtectProc=invisible` with `ProcSubset=pid`, `RestrictNamespaces`, `RestrictRealtime`, `RestrictSUIDSGID`, `LockPersonality`, `RemoveIPC`, `UMask=0027`, and `SystemCallFilter=@system-service` with `SystemCallErrorNumber=EPERM`. `RestrictAddressFamilies` keeps `AF_NETLINK`: it reads as an unnecessary family for a web service, but glibc opens it while choosing a source address during `getaddrinfo`, so removing it breaks name resolution for the `prod` database and nothing in the `dev` smoke test.
- **Two directives are forbidden rather than omitted.** `MemoryDenyWriteExecute=yes` aborts HotSpot, which executes pages it has just written, and `PrivateUsers=yes` remaps the `DynamicUser` away from its own `StateDirectory`. Both turn the unit into a service that never starts, so the gate fails if either appears instead of trusting a comment.
- **The gate installs the package and boots the unit.** `scripts/smoke-linux-packages.sh` checks the 26 directives on the unit *inside the `.deb`* — a unit that never reached the payload is the failure that matters — and with `SMOKE_UNIT_BOOT=1` (set by the *Linux packages* and *release* jobs) installs the package, starts the unit, probes `/health` for `engine: available`, the HTML console, `DynamicUser` with its created `StateDirectory`, and a restart, then purges it. Contents checks cannot see a sandbox that fails to mount: that fails at runtime, after `systemd-analyze verify` has already called the unit well-formed.
- **Measured on a real install.** On systemd 262 the installed unit answers `/health` with `engine: available`, serves the console as `text/html`, comes back healthy after `systemctl restart`, and scores **1.5/10 (OK)** under `systemd-analyze security`; the only remaining flag is the group-readable `UMask=0027`, kept because the state directory is group-traversable. Mutating the unit to drop `ProtectSystem=strict`, to add `MemoryDenyWriteExecute=yes` or to remove `AF_NETLINK` each fails the gate with the directive named.

### Fixed — installing the .deb no longer fails on a headless host

The boot gate below found this on its first real run: `dpkg -i pad-liveness_*.deb` exits 1 on a machine with no desktop, because jpackage's postinst runs `xdg-desktop-menu install` under `set -e` and that command fails when no system menu directory is writable. dpkg leaves the package half-configured — the files are unpacked under `/opt`, the unit is never set up, and the next upgrade inherits the mess — on exactly the servers this `.deb` targets.

- **A cosmetic step no longer decides the install.** `scripts/package-linux.sh` appends `|| true` to the `xdg-desktop-menu install`/`uninstall` lines in the repacked postinst and prerm, next to the `daemon-reload` hook it already injects. A desktop still gets its menu entry; a headless host installs, and the unit can start.
- **Verified where it used to fail.** A real `.deb` built inside a systemd Ubuntu 24.04 container without `/etc/xdg/menus` installs cleanly, boots the unit under the sandbox, serves `/health` with `engine: available`, restarts healthy, and purges from the trap. The packaged unit is byte-identical to `packaging/linux/pad-liveness.service`.

### Added — releases carry the Linux installers

A `v*` tag published both jars and their checksums, while the `.deb` and the AppImage existed only in `target/dist` for whoever built them and in CI artifact storage that expires after seven days. The two artifacts a Linux host actually installs were the only ones a release did not ship.

- **Built in the release job, from the tag's version.** Downloading the packaging job's cache would not do: those installers are built with the default `-Drevision`, so their names and payload carry a snapshot version. The release runs the same `linux-package` profile with `-Drevision=$VERSION` in the slim build it already needed, and `scripts/package-linux.sh` prefers the slim jar — so each installer contains exactly the artifact the release stamps and checksums.
- **Strict, so neither half can quietly go missing.** `-Dlinux.package.strict=true` makes a skip fatal. The job installs `dpkg`/`fakeroot` if the runner image lacks them, and caches appimagetool 1.9.1 (verified against its pinned sha256) with the same key as the packaging job.
- **Gated before publication, not after.** `scripts/smoke-linux-packages.sh` inspects the `.deb` payload, unit and `daemon-reload` hook and boots the AppImage against `/health` and the console, then the files are staged and checksummed. A release can no longer publish an installer that runs nowhere.
- **Eight assets or no release.** The attach step globs the staged files and fails unless it has exactly two jars, one `.deb`, one AppImage and a `.sha256` beside each — `gh` skips a missing file without failing, which is how a release ends up with no slim jar and nobody notices. The globs matter for the `.deb` too: jpackage owns that file name and appends its own Debian revision.

### Added — dependency choices are gated, not remembered

The Jackson 2 coupling showed the shape: a class compiles against something the service does not run on, nothing fails, and the breakage surfaces only after an upgrade changes the library underneath it. `JacksonRuntimeGateTest` closed that one door. `DependencyOwnershipGateTest` now closes the rest of the same class of mistake, and it fails closed — an import it cannot attribute to a jar is a red test, not a silent pass.

- **Ownership.** Every non-JDK import under `src/main/java` and `src/test/java` resolves to the jar that provides it, and that artifact must be declared in `pom.xml` or owned by a declared dependency in a ledger that carries the reason. Wildcard imports (`import org.opencv.core.*`) and static wildcard imports (`Mockito.*`, which name a class rather than a package) resolve too, so the gate no longer has a blind spot shaped like `*`; the ledger's other half fails an entry nothing imports any more.
- **Scope.** A `provided` dependency is on the compile and test classpath but not in the jar, so importing it outside the host-integration package is the purest compile-only-by-accident: `javac` is happy, surefire is happy, and the packaged service throws `NoClassDefFoundError` at the point of use. `HOST_PROVIDED` names the package allowed to use each provided dependency; a provided dependency with no entry, or an import outside its package, fails.
- **Packaging.** The same distinction is checked from the other side: every `provided`/`optional` dependency must be excluded from the repackaged jar (the JavaFX modules once shipped in `BOOT-INF/lib` even though the Registration Client supplies them), every base exclusion must be a `provided`/`optional` dependency whose owning module is host-supplied (excluding something the service calls at runtime produces a jar that packages cleanly and fails on the first request), and the `slim` profile must restate the base exclusions, because Maven merges profile plugin configuration positionally.
- **Version lockstep.** A library split into modules is built as one release; a half-upgraded family links fine and then fails with `NoSuchMethodError` on whichever path reaches the changed method. This build carried one: the pom pinned `junit-jupiter` to 6.1.3 while spring-boot-starter-parent's dependency management kept `junit-jupiter-api`, `-params` and `-engine` on 6.0.3 — every test green and the engine a version the pom never mentioned. The aggregator's pin is gone (the parent owns JUnit's version) and the gate now fails any group that resolves to more than one version, with a named list of groups that are genuinely independent libraries so the rule cannot become a rubber stamp.
- **Verified to fail, not just to pass.** A temporary `javafx` import in a service class, a removed `javafx-controls` exclusion and the real JUnit split each turned the corresponding check red with a `file:line` or artifact-level message, then were reverted.

### Added — `./mvnw package` builds the Linux installers

On Linux the `package` phase now also produces `target/dist/pad-liveness_<version>-1_amd64.deb` and `target/dist/pad-liveness-<version>-x86_64.AppImage`, so the two artifacts people actually install no longer need a manual jpackage invocation.

- **The wiring is a profile, not plugin configuration.** `linux-package` is activated by the OS rather than `activeByDefault` (which any `-P` on the command line switches off, so `./mvnw package -Pslim` would have silently stopped building installers) and it calls `scripts/package-linux.sh` from the `package` phase, after repackage has written the jar. jpackage is a CLI over an application image and the AppImage step has no Maven equivalent, so there is nothing to express as plugin goals.
- **The `.deb` carries a working systemd unit.** jpackage can only install files that come out of the app image, so the unit is added by repacking: `dpkg-deb -R`, install `/lib/systemd/system/pad-liveness.service`, append `systemctl daemon-reload` to the postinst/postrm jpackage generated, `dpkg-deb --build --root-owner-group`. The unit is installed but not enabled — binding port 8000 stays a decision for whoever installs it — and it runs the `dev` profile, so a fresh package starts on a machine with no PostgreSQL.
- **The AppImage's `AppRun` starts the service, waits for `/health`, then opens the console** at `http://127.0.0.1:8000/`, with `PAD_LIVENESS_PORT`/`PAD_LIVENESS_PROFILE` overrides. It also polls for the image's own mount disappearing, so a torn-down AppImage cannot leave an orphaned JVM serving from a vanished mount.
- **Missing tools are a warning locally and a failure in CI.** jpackage ships with the JDK the build already needs; `dpkg-deb`/`fakeroot` and appimagetool do not, so a half whose tool is absent is skipped with a warning (`-Dlinux.package.strict=true` makes it fatal). appimagetool is fetched once per machine from its pinned 1.9.1 release and verified against a hard-coded sha256, because that project's `continuous` tag moves.
- **A new *Linux packages* CI job** builds with `-Dlinux.package.strict=true` and runs `scripts/smoke-linux-packages.sh`, which asserts the `.deb` installs `/opt/pad-liveness/bin/pad-liveness` (executable, exactly the path the unit's `ExecStart` names), that the unit and its `daemon-reload` hook are in the archive, and that the AppImage boots and serves `/health` with `engine: available` plus an HTML console at `/`. A green build that produced only one installer now fails instead.

### Added — the pipeline builds the Windows installer too

The Windows artifact used to be a jar plus `start.bat`, which is not what most Windows deployments install. `\mvnw.cmd package` now also produces `target\dist\pad-liveness-<version>.exe` from the same build that writes the jar.

- **`windows-package` profile, activated by the OS**, calling `scripts/package-windows.ps1` from the `package` phase after repackage — the same shape as the Linux profile, and for the same reasons: inert on Linux/macOS, and not switchable off by a stray `-P` the way `activeByDefault` would be.
- **The application image is built with `--win-console`.** This is a server, not a windowed app: without it the launcher has no console and the startup log, a port conflict or a startup failure go nowhere a user can see them. The installer adds a per-user install (no elevation), a Start-menu entry under **MOSIP** and a desktop shortcut.
- **The launcher's default arguments pin the `dev` profile**, so a fresh install starts on the in-memory database with no PostgreSQL running, exactly like the Linux installers. Passing any argument (for example `--spring.profiles.active=prod`) replaces those defaults.
- **It refuses the `-Pslim` jar.** The slim profile keeps linux/x86_64 natives only, so an installer built from it would install, start, and report the engine unavailable to every frame. That is an error rather than a skip.
- **`--win-upgrade-uuid` is pinned** so a newer installer replaces the previous build instead of installing beside it and leaving two copies under Apps and Features.
- **A new *Windows installer* CI job** installs WiX Toolset 3.14 (jpackage's only supported WiX major), builds with `-Dwindows.package.strict=true` so a missing toolchain cannot pass as a skipped half, and runs `scripts/smoke-windows-installer.ps1`: the `.exe` is read as a PE image whose version resource matches its file name, then the application image it deploys is booted and probed for `engine: available` and an HTML console at `/`. The installer's own interactive install/uninstall is deliberately not automated.
- **The build is unsigned**, so SmartScreen warns on first run; README says so rather than leaving it to be discovered.

### Fixed — the Spring Boot 4.1.1 upgrade is now actually complete

The dependency bump to `spring-boot-starter-parent` 4.1.1 and springdoc 3.1.1 was merged on its own; the code around it was still written for Boot 3.3.3. `./start.sh` died at startup and the Java test tree did not compile at all.

- **Startup failed with `No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps`.** Boot 4 binds `spring.jackson.serialization.*` onto Jackson 3 (`tools.jackson`), which dropped the feature: instants are always ISO-8601 strings now. The Jackson 2 key was removed from `application.yml`; the wire format is unchanged (verified against `/api/v1/auth/login` and the error envelope, both still emit `2026-10-20T09:33:34.524010251+05:30`-style instants).
- **Flyway never ran, so a fresh database failed `ddl-auto: validate` with `missing table [audit_logs]`.** Boot 4 moved Flyway autoconfiguration out of `spring-boot-autoconfigure` into `spring-boot-flyway`; declaring `flyway-core` alone is no longer wired. The pom now depends on `spring-boot-starter-flyway`, and the PostgreSQL schema test (real Testcontainers database, real migrations) passes again.
- **All 24 test classes that used a Boot test slice failed to compile.** Boot 4 split the slices into per-technology modules: `WebMvcTest`/`AutoConfigureMockMvc` moved to `spring-boot-webmvc-test`, `DataJpaTest` to `spring-boot-data-jpa-test` (which brings `TestEntityManager` and `AutoConfigureTestDatabase`), and `TestRestTemplate` to `spring-boot-resttestclient` — now behind `@AutoConfigureTestRestTemplate`, and needing `spring-boot-restclient` for the `RestTemplateBuilder` its autoconfiguration inspects. All three are test-scoped dependencies and the imports were moved with them.
- **The controller tests injected a Jackson 2 `ObjectMapper` that Boot 4 no longer exports.** Spring MVC serializes with Jackson 3 now, so the tests that round-trip HTTP bodies use `tools.jackson.databind.ObjectMapper`, and Jackson 3's `JsonNode.propertyNames()` replaces the removed `fieldNames()`.
- **Hibernate 7's `merge()` throws where Hibernate 6 inserted.** Two audit-chain tests persisted entities with an explicitly assigned id into an empty row; `DefaultMergeEventListener.entityIsDetached` now raises `StaleObjectStateException` when the row is missing and the id is supplied. One test lets the UUID generator assign the id, and the re-insert that restores a deleted audit row now goes through `JdbcTemplate`, which is also more faithful to what that test is proving (exact bytes back, no JPA re-derivation of the hash).
- **The headless JavaFX tests could not start on the JDK 17 the project targets.** `javafx.version` had been bumped to 21.0.2 while `openjfx-monocle` 21.0.2 is class-file 65 (Java 21), so the toolkit died with `UnsupportedClassVersionError`; it is pinned back to 17.0.10, matching `<java.version>`, the CI runners and the pom's own note that Monocle must track the JavaFX it runs on.
- README, `docs/PRD.md`, `docs/brain.md` and the Android README now name Boot 4.1.1, springdoc 3.1.1, JUnit 6 and Surefire 3.6 instead of the versions from before the bump.

### Changed — the service no longer runs on Jackson 2

The Boot 4 upgrade above left one coupling behind: the JPA JSON converters and `LivenessHttpClient` still imported `com.fasterxml.jackson`, which compiled only because springdoc pulls Jackson 2 in through swagger-core. That is the mapper the service was not using, so a change in Boot's mapper could not be caught by the compiler.

- **All production JSON now uses Jackson 3 (`tools.jackson`).** `StringListJsonConverter`, `StringObjectMapJsonConverter`, `EffectivePolicyJsonConverter` and `LivenessHttpClient` hold a `tools.jackson.databind.ObjectMapper` (`JsonMapper.shared()` for the stateless ones). `JsonProcessingException` no longer exists — Jackson 3's `JacksonException` is unchecked, so the converters keep wrapping it in `IllegalArgumentException` but catch the new type. `ObjectMapper.configure()` was removed with the mutable mapper, so `EffectivePolicyJsonConverter` disables `FAIL_ON_UNKNOWN_PROPERTIES` on `JsonMapper.builder()`.
- **The six tests that still used Jackson 2 moved with it**, including `fieldNames()` → `propertyNames()` and the `JavaTimeModule` registration that Jackson 3 folds into databind. `JacksonRuntimeGateTest` now fails on any `com.fasterxml.jackson.core`/`.databind` import under `src`, so the coupling cannot return unnoticed.
- **`pom.xml` declares `tools.jackson.core:jackson-databind` directly** instead of inheriting it from Flyway or springdoc, and records why the Jackson 2 jars are still in the tree: springdoc 3.x emits `/v3/api-docs` through swagger-core 2.2.x, which is Jackson 2 based. That is swagger-core's internal mapper; the service's code may not touch it. The DTOs' `@JsonInclude` imports stay in `com.fasterxml.jackson.annotation` because Jackson 3's databind depends on `jackson-annotations` 2.x, which keeps the old package.
- **The gate reads the pom, not just the imports.** `JacksonRuntimeGateTest` now fails on a Jackson 2 coordinate declared in `pom.xml` as well — every `<dependency>` in the document, including profile-scoped ones and `<dependencyManagement>` — naming the artifact and the section it sits in. An import is one line to undo; a declared `jackson-databind` or `dataformat-*` module is a deliberate version pin on the classpath the service runs from, and that is the state this gate exists to make unreachable. A BOM import is exempt: it ships no jar of its own, so it cannot put Jackson 2 on the classpath until an artifact from it is declared.
- **`docs/jackson-3.md` writes the rules down** — the `tools.jackson` packages, immutable `JsonMapper` builders, `JacksonException`, the shared annotation API and why a Jackson 2 import still compiles — and `src/AGENTS.md` points at it, so the next mapper does not have to rediscover the coupling from this changelog.

### Added — Anti-replay protection, evidence verification, and unified localisation

- **Session anti-replay validation (`AntiReplayValidator` + `V13__session_anti_replay.sql`)**: Sessions now generate cryptographically secure 256-bit nonces bound to `liveness_sessions.nonce`. Prevents replay attacks across capture attempts.
- **Client evidence verification endpoint (`POST /api/v1/sessions/{sessionId}/evidence`)**: Checks evidence produced by online client gates (`EvidenceVerificationRequest`, `EvidenceVerificationResponse`, `LivenessEvidenceVerifier`). Rejects a session that is not `ACTIVE`/`PASSED`, a nonce that does not match the session's (`V13`), timestamps in the wrong order, and any failed challenge result; warns on a bypassed gate or a missing signature. The endpoint checks that `signatureHex` is present, **not** that it verifies — client-side signing and verification exist (`LivenessEvidenceBinding`, `EvidenceSigner`) but server-side cryptographic verification is not wired in yet.
- **Unified localisation message catalog (`LivenessMessageKeys` + `app_en.arb`)**: Centralised all user-facing guidance and error keys across Java and Flutter/Android. Enforced by `LocalisationKeyGateTest`, ensuring all message keys map to valid localized text without exposing internal error or model details.
- **Repository cleanup**: Removed obsolete third-party workspace (`vendor/github setup/`) and design mockups (`stitch_markdown_ui_page_designer/`), keeping the repository focused strictly on the active MOSIP liveness and PAD implementation. Updated repository guidance (`AGENTS.md`, `README.md`, `PRD.md`) to reflect the 5 active project trees.
- **Build hardening (`scripts/check-java-imports.mjs`)**: Restored the thirteen classes lost when `0824f02` deleted `src/` — the engine primitives, backend SPI and test helpers that the remaining files still imported — so `./start.sh` builds and boots again. A new CI job fails whenever an `io.mosip.liveness.*` import under `src` names no source file that declares it, so a partial source-tree restore is a one-line diagnosis instead of pages of `cannot find symbol` against the wrong files.

### Fixed — the CI jobs that were red on every push

- **A JDK 25 runner broke every Mockito-backed suite.** Commit `6506a40` raised every job from JDK 17 to 25, but the Mockito/ByteBuddy pair Spring Boot 3.3.x manages cannot instrument JDK 25 class files. Reproduced locally on Temurin 25.0.4.1: 732 tests, 231 errors, every one `Mockito cannot mock this class` before an assertion ran — which is what the Unit, Integration, PostgreSQL-schema and Cross-backend jobs were reporting. The workflow is back on JDK 17 (the version `<java.version>` targets), the Windows launcher keeps its own JDK 21 install, and `actions/setup-java` moves to v5, the version the runner's deprecation notice asks for.
- **The Android job could not compile the engine it embeds.** `android/app/build.gradle` compiles a hand-picked subset of `src/main/java`, and `io/mosip/liveness/shared/**` was missing from it even though `EffectivePolicy`, `FaceLivenessEngine`, `AndroidLivenessOrchestrator` and `LivenessGatePolicy` all import `RepeatedFailureAction` from there. The include list now carries the package: the subset compiles under `javac` on 17, and deleting the entry again reproduces the CI error (`package io.mosip.liveness.shared.definitions does not exist`).
- **The secret scan found 52 findings.** Forty are `curl -H` samples copied from a third-party NOAA API guide into vendored agent-skill trees (`.agents/`, `.claude/`, …, since removed — but the gate reads history), and twelve are the API tests' deliberately fake operator keys (`synthetic-operator-key-…`, `integration-operator-key-…`). `.gitleaks.toml` allowlists exactly those two families — the curl rule only under that one guide's path, the generic rule only on lines carrying those prefixes — and the scan is green again. Verified the same value outside `vendor/` still fails, so the historical allowlist has not gone blind.
- `QUICKSTART.md` and `docs/PRD.md` said JDK 25 is the runtime; they now state the JDK 17+ the build and its tests actually require.
- **The Java layout gates gained their second half.** `scripts/check-java-imports.mjs` proves every import names a source file that declares it, but a file moved to the wrong directory — or a package declaration edited to one the filesystem does not support — still resolves by path, so that gate stays green while `javac` fails for every consumer. `scripts/check-java-packages.mjs` now compares each file's declared package with its directory (and rejects a package declaration at a source root, where only the default package belongs); both run in one *Java source layout* job. An unresolved import is also annotated with its history: the commit that removed the class and the `git show <sha>^:<path>` command that restores it, or, when the path never held it, the last commit to touch a file of that name anywhere — so the repair starts from the deletion instead of a search. Outside a git checkout the annotation is simply omitted.

### Changed — The console implements the reference design system, and the review fixed what only looked right

- `src/main/resources/static/` now implements the modern institutional enterprise design system:
  the palette (cobalt `#1E56D0` over a `#F8FAFC` canvas in white containers, `#E2E8F0` borders, the four status triads), a 32px display heading,
  the two-line MOSIP / Liveness Console identity, the 1440px content cap, a split
  capture layout (preview beside its controls — the old single column pushed Run
  and Stop below the fold on a laptop), the hardened `#020617` camera surface with
  a dashed guide, calibration ticks and a monospace HUD, and a footer strip.
  Inter and JetBrains Mono are used when the workstation has them; no font or CSS
  CDN is loaded, because the console ships under `style-src 'self'`.
- The viewfinder reports facts rather than decoration: the HUD's left side is the
  capture budget actually in force (`window.CAPTURE_BUDGET`) and its right side the
  stream's own state, the card's pill flips **Camera ready → Camera live** with the
  stream, and the guide's colour follows the verdict the server returned (white
  dashed → cobalt while analysing → amber while a challenge is open → green on a
  pass, red on a rejection). The resident "Current step" card is driven by the run
  (title, note, progress, `Stage n of 3` row) instead of sitting on fixed markup,
  and a capture-facts card shows the same frozen budget object the capture loop
  reads.
- **Fixed: `[hidden]` did nothing.** The component rules set `display` on the same
  elements, so the author rule beat the user-agent's `[hidden] { display: none }`.
  The repair commit's own restyle is what introduced it, and it put an operator in
  front of the supervisor-only *Load policy* / *Sign out* buttons and every admin
  tab — Policy, Users, Metrics, Diagnostics, Chain, Sweep — none of which their
  role exposes. One base rule restores the switch, and the operator tab row is the
  four read-only views again.
- **Fixed: the Sessions table invented its columns.** Rows were defaulted to
  `PASSED` / `0` / `—` whenever the tab knew nothing. The creation response's
  status is kept, `GET /sessions/{id}` fills status, retries and reason after a run
  settles and on Refresh, a close records the status it returned, and a column with
  nothing known says `UNKNOWN`. The design's populated table is still what a fresh
  page shows — as placeholder rows, each tagged `SAMPLE`, its Open action disabled,
  with a footnote saying the service has no session-list endpoint.
- **Fixed: "Switch role" / "Log out" left the workstation running.** The button
  called `signOut()`, so an open camera kept streaming and an open session stayed
  `ACTIVE` server-side; `switchRole()` — written for exactly this and previously
  unreferenced — now runs: camera released, session closed ("closed on role
  switch: status=EXPIRED"), run state discarded.
- **Fixed: a declined first password change bricked the page.** Signing in on an
  account that still owes its first password change and refusing the prompt left a
  session the service confines to the change-password route, so every later request
  — including the document itself — answered `403`, and the console could not be
  reopened from that browser until the cookie expired. The refusal now ends the
  session and says what is needed.
- **The console asks for a credential in its own dialog.** Every `window.prompt()`
  is gone (the guards fail if one comes back): authorising a policy save and
  setting a first password both open `#auth-modal`, the screen the reference
  actually drew — lock chip, an account that is read-only once it is known, the
  password field with a "Session re-auth required" hint, Cancel and a
  **Confirm & Apply Policy** / **Set password and continue** primary. It matters
  beyond looks: a platform prompt cannot say what it is asking for, returns the
  same empty string for "cancelled" and "typed nothing", and some embedded
  webviews suppress it entirely — which is how a first-time supervisor could be
  asked nothing and then told to sign in again. A too-short or unchanged new
  password is answered in the dialog before any request; the password is cleared
  from the field on close and never stored. Verified in a browser end to end:
  bootstrapped `supervisor` signing in from the role picker sets its password in
  the dialog and lands on Policy, and a save after the session is dropped opens
  the dialog and then reports `Saved RESIDENT policy.` The policy sidebar's
  credential note is now the neutral slate card with a lock icon, matching the
  mock, rather than an amber warning about a flow that is no longer unusual.
- `src/main/resources/static/console.css`: the policy/form notes read neutral
  slate rather than amber — DESIGN.md keeps amber for *active / checking* — and the
  tab row no longer spawns native scrollbar arrows over the last tab (a 1px
  descender overhang under `overflow: auto`; the row is one line, so the axis is
  hidden).
- **Fixed: the Users tab did nothing.** `users` was missing from the console's
  tab list, so the tab had no click handler and its panel never opened — and the
  panel was only two paragraphs of placeholder text. It now switches and lists the
  accounts the service holds (`GET /api/v1/users`) with each account's own role and
  state flags (disabled outranks an owed password change) and its creation time,
  and it says that create / role change / disable / reset are not wired into the
  table, rather than offering buttons that do nothing.
- `src/test/js/console-guards.test.mjs` grew to **133 assertions** (from 96),
  pinning the two honesty rules — placeholders are tagged and unopenable, and the
  status, retry count and reason columns come back from `GET /sessions/{id}` rather
  than from a default — and, in two new sections, the credential dialog: no
  `window.prompt` in the code (comments naming the removed call are stripped before
  the check), an open dialog sends nothing, Cancel sends nothing and aborts the
  save, the answer typed in is what goes on the wire, a first sign-in asks for the
  new password and refuses a too-short one locally, and the password is cleared
  from the field and appears nowhere in the page's text afterwards. The harness's
  DOM now honours the bare `hidden` attribute, as a browser does, so an assertion
  about a dialog's starting state is meaningful. `docs/ui-ux-design.md` §7
  documents the design system, the truth-in-UI rules, the divergences from the
  mocks, and (§7.7) the single dialog and why nothing is asked through a platform
  prompt.

### Docs — the reference documents were reconciled with the tree

A full documentation pass re-checked every count, route and backend claim against
the code and the current test run. `docs/PRD.md` now states the real sizes (190
main classes, 88 test classes, 751 Java tests, 49 Python tests, 12 migrations, 15
CI jobs, 9 gate scripts), lists `POST /sessions/{id}/active-liveness`, `/evidence`
and `/auth/login` in §7, and includes the import, package and secret gates in the
repository-gate table; the CI job list gains the *Java source layout* job.
`docs/status-report.md` (190 files, ONNX as the default backend, accounts and
anti-replay rows) and `docs/brain.md` (inventory, test counts, schema through V13,
an evidence-endpoint section) describe what exists rather than the phase-1 library.
`README.md` documents the missing routes and the corrected project layout,
`QUICKSTART.md` states the container's real layer size, `docs/backend-swapping.md`
documents `OnnxMiniFasNetBackend` as the desktop default with the selection table,
and `docs/known-quirks.md` records that its characterization tests are gone. The
evidence endpoint is described for what it checks: signature **presence**, not
validity (client-side signing exists; server-side verification is not wired in).

### Performance — the console's script crosses the wire compressed, and three startup knobs were measured out

A report that the console "takes a long time to load" was measured before
anything was changed, and the page was not the slow part: on this host a warm
navigation reached `DOMContentLoaded` in **0.26–0.66 s**, while a `dev` start took
**20.9 s** to answer `/health`. One real delivery defect was found and fixed; the
startup knobs were measured and mostly rejected; the dead-code search came back
empty.

- **Fixed: the console's 113 KB script was served uncompressed, on every load.**
  `server.compression.mime-types` *replaces* Spring Boot's default set, and the
  default is where `text/javascript` came from — which is the type Spring serves a
  `.js` resource as (RFC 9239). Both spellings are now listed. Measured on a cold
  load: `/console.js` **115 565 → 32 766 bytes** (−71.6 %), and the console's three
  files together **133 852 → 51 053 bytes** (−61.9 %; uncompressed they are
  197 613).
- **The console's files carry `Cache-Control` now**
  (`spring.web.resources.cache.cachecontrol`: `max-age=600, must-revalidate,
  public`). They carried none, so every reload re-fetched or revalidated
  ~190 KB that cannot have changed. A repeat navigation now reports
  `transferSize: 0` for both `console.css` and `console.js` — no request at all —
  and `must-revalidate` keeps `Last-Modified`, so the cost after the window is one
  conditional `304` per file. The window bounds a stale console to ten minutes
  after a service restart that ships new files.
- **No minifier, bundler, build step or dependency was added.** gzip removes the
  bytes; the comments keep the reasoning in the source where it belongs.
- **Three startup knobs were measured and rejected** (`dev`, one run each, stop at
  `/health`): `spring.main.lazy-initialization=true` 21 079 ms against a 20 860 ms
  baseline (inside noise, and it moves wiring failures to the first request),
  `-XX:TieredStopAtLevel=1 -Xshare:auto` 21 572 ms (the cost is class loading and
  Hibernate, not JIT), and `java -jar` on the built fat jar **30 159 ms — worse**,
  because class loading goes through 244 MB of nested jar. The start is JVM + Maven
  + Hibernate (5.6 s Maven bootstrap, ~5 s compile, 2.8 s component scan over 139
  jars, 3.4 s Hibernate metamodel and schema), so none of them had anything to cut.
- **Nothing dead was found to delete.** `console.js`: 79 functions, all
  referenced. `console.css`: 185 class selectors, 172 referenced literally — the
  other 13 are composed at runtime (`"pill " + kind`) and removing them would
  break a state. `index.html`: 174 ids, 27 invisible to a grep for the same reason
  (`$("tab-" + t)`).
- `docs/performance-baseline.md` §11 records the before/after table, the rejected
  knobs with their numbers, the startup breakdown and the limits of the
  measurement (host load 3.3–5.0, one sample per configuration, warm caches — not
  §5.1's cold-cache CI parity figure).

### Added — A low-resource launch (`--lowmem`) for the 2-core / 2 GB target

- `./start.sh --lowmem` / `start.bat --lowmem` start the service the way the
  target registration-client machine needs it: `-Xmx512m`, `-XX:+UseSerialGC`, a
  class-data-sharing archive when one has been trained, and the opt-in `lowmem`
  Spring profile. The launchers print the JVM options and the profile they used,
  and **nothing changes without the flag** — the default launch is the same
  command it was before this option existed.
- **Measured, five cold boots per side, alternating baseline and tuned** so the
  shared host's load drift hits both (2 pinned cores, OpenCV native cache cleared
  before every boot, `engine: available` required): boot to `/health` **23.1 s →
  19.0 s** (−4.1 s, −17.7 %; min 21.2 → 18.0 s) and RSS at `/health` **314.3 MB →
  276.7 MB** (−37.6 MB, −12.0 %), with threads 38 → 31. Every tuned boot beat
  every baseline boot on both axes, and a second interleaved run earlier in the
  session repeated it (22.3 → 19.0 s, 320.6 → 274.4 MB).
- **Per flag**, each in its own interleaved A/B: class-data sharing **−2.2 s**
  (−10.4 %) of boot and −11.4 MB; `-XX:+UseSerialGC` **−41.2 MB** (−12.9 %) and
  seven fewer threads; `-Xmx512m` **−25.4 MB** (−8.2 %) with no boot cost. The kept
  flags do not add up — Serial GC and the heap cap act on the same heap footprint
  (−78 MB on paper, −37 to −46 MB measured).
- **Nothing Spring-side earned a place.** Tomcat's thread ceiling (50 → 20) and
  the Hikari pool (5 → 2) moved RSS by −1.8 %, inside the sample spread, with no
  boot change; `-Xss512k` saved nothing; disabling the Swagger/OpenAPI surface was
  flat over 14 paired boots (SpringDoc builds the document on the first request to
  `/v3/api-docs`, not during startup); quieter logging was flat too. All five are
  recorded with their numbers in `application-lowmem.yml`, which therefore sets
  **no property** — it ships as the documented extension point.
- **Lazy initialization was the fastest candidate and is rejected on behaviour,
  not speed**: `spring.main.lazy-initialization=true` moves the dispatcher
  servlet, the entity manager and the schema step from boot to the first request,
  so the service would answer `/health` before a migration had run and a schema
  error would surface as a failed first frame. It is called out in the profile and
  the docs so nobody re-proposes it as a free win.
- Class-data sharing ships as a **documented one-time training step**, not an
  automatic one: the archive is tied to the exact jar and is written at JVM exit,
  which neither launcher can guarantee (`start.sh` stops with a 4-second grace,
  `start.bat` uses `taskkill /F`). The launchers use it when it is present and
  newer than the jar, and say whether sharing is active; a missing or stale archive
  is a JVM warning, never a boot failure (verified).

### Changed — A capture budget on the browser console, and a configurable analysis cap

- The console sent the camera's **native** frame: 1280x720 on a webcam that
  ignores the `640x480` `ideal`, encoded at quality 0.85 (0.9 for challenge
  frames) — 921 600 pixels drawn, encoded, base64'd and uploaded per frame, for a
  face the service shrinks to its own analysis cap anyway. `static/console.js`
  now draws the whole source frame into a canvas whose long edge is at most
  **640 px** and encodes at **quality 0.7**, with the ceilings, the pause and the
  drop as named constants (`CAPTURE_MAX_EDGE`, `CAPTURE_JPEG_QUALITY`,
  `CAPTURE_MAX_FPS`) plus a read-only `window.CAPTURE_BUDGET` for the guards.
  Measured in Blink on the same 720p frame: payload **42 853 -> 12 033 B**
  (-71.9 %, -76.2 % on the challenge encode) and draw+encode **79.6 -> 19.4 ms**
  (-75.6 %); per-frame server cost for the genuine-face fixture **428.7 -> 162.3
  ms** total (-62.1 %, decode -77 %, face detection -60 %, PAD model -70 %).
- **Never more than eight frames per second, never two requests at once.** One
  gate (`capturePaused()` + `awaitCaptureSlot()`) now wraps every frame and
  challenge POST, so the two capture screens (Supervisor and Resident) that share
  one camera and one canvas cannot combine into 16 sends/s on a 2-core box, and a
  capture taken while a request is in flight is **dropped** — the caller retries
  on its next tick — instead of queued behind it. A skipped tick no longer spends
  one of a run's twelve attempts, so a pause costs throughput, never attempts.
- **Capture pauses while the tab is hidden** (`document.hidden`), with a line in
  the decision log, because nobody can read the prompt or perform a challenge in a
  tab they cannot see; the camera stream itself stays open, since stopping a
  `MediaStreamTrack` makes the camera re-acquire with a visible flash.
- The challenge burst is deliberately **unchanged** (baseline + up to the 4-frame
  window, 4 on the first poll): the engine measures an excursion relative to the
  baseline, so trimming the window would change what it evaluates. What changed is
  the price of each frame in it and the shared 8/s ceiling.
- New `mosip.liveness.analysis-max-dimension` (default **1280**, the previous
  hard-coded value) makes `ImageUtils`'s long-edge cap configurable. It is applied
  after the fixed payload/dimension/pixel caps and **before** face detection,
  quality scoring and PAD, and values below 64 stop the boot instead of being
  clamped. It is a cost bound, not a free knob: on the same 720p frame, cap 640
  costs 151.4 ms instead of 428.7 ms but moves the genuine-face score from 0.9495
  to 0.6444 (below the 0.80 threshold), and cap 480 loses the face entirely
  (`faceDetected=false`). 1280 only means a 1080p camera costs what a 640x480 one
  does, and the console now captures below it anyway.
- **Decisions did not move, scores did.** On both fixtures the decision fields are
  identical before/after (face detected, `padFlag`, `action=retry_passive`), but
  the numbers behind them are not: the genuine fixture's passive score reads
  0.9495 -> 0.8555 (still above the 0.80 threshold) and the print fixture's PAD
  label reads `PRINTED_PHOTO` (heuristic) -> `SCREEN_REPLAY` (model), because at
  640 px the heuristic no longer short-circuits the model pass. Fewer pixels and
  fewer JPEG bits reach the model; that is the trade-off the capture budget buys,
  and it is documented in `docs/performance-baseline.md` §10 with the full
  before/after table.

### Changed — One detection and one inference per frame, and a configurable ONNX thread count

- One passive frame used to pay **three** Haar cascades and **two** MiniFASNet
  inferences inside `OnnxMiniFasNetBackend`: `analyzeFrame` detected the face, and
  each of `scorePassiveLiveness` and `assessPad` re-detected and re-inferred on
  its own. The backend now memoizes one frame's analysis — face count and box,
  quality, raw class probabilities — keyed by the frame's **pixel content**
  (CRC32 + CRC32C + geometry, stamped with a model generation bumped on load and
  unload), one slot per thread and derived values only. The engine's per-frame
  conversation and the HTTP path's rebuilt frames therefore share one detection
  and one inference. Measured on the same host and harness: **160.9 → 61.8 ms**
  per distinct frame at the SPI seam (16.2 fps) and **304.7 → 167.8 ms** per
  passive HTTP frame; a repeated identical frame drops to 0.24 ms and 79.4 ms.
  Detection and inference counts, not just wall clock, are pinned by
  `OnnxMiniFasNetBackendTest`.
- **No returned value moved**: `Double.toHexString` equality against values
  captured from the pre-change code for the genuine-face and print-attack
  fixtures, plus the end-to-end HTTP score that matches the pre-change baseline
  exactly. Thresholds, median window, PAD confirm frames, challenge logic, policy
  handling, rate limiting, audit chain, headers and key checks are untouched.
- Native buffers on the per-frame paths are now released in `finally`
  (`ImageUtils` results and placeholder `MatOfDouble`s, `LivenessEngineService`
  ROI/Laplacian buffers, `PadEngineService`'s six buffers). The Haar cascades
  needed no change — the only construction sites are lazy/initialization paths.
- New `mosip.liveness.onnx-intra-op-threads` (backend option `intraOpThreads`)
  controls how many threads one inference may use. Unset keeps `max(1, cores/2)`,
  which is **one thread on the 2-core target**. On this 4-core host one thread also
  measured fastest — 49.5 ms per distinct frame, against 54.1 ms at two threads and
  123.3 ms at four — and the count changes no score on either fixture. A
  negative or malformed value stops the boot instead of silently deriving a
  default, and the backend option itself accepts only a positive integer.
- `BackendPerFrameLatencyTest` now measures two workloads (`repeated-frame`,
  `distinct-frames`) and labels them in the results line, because a memoized
  backend makes a repeated input measure nothing.

### Added — Windows launcher boot gate

- Every gate in this repository ran on Linux, so the two things a Windows user
  actually touches were covered by hand only: `start.bat`, and the Windows
  native inside the full jar. The launcher's version probe read `java -version`
  — which prints on stderr — through `cmd.exe`, and cmd strips the quotes around
  an executable when a `for /f` command contains a pipe, so a JDK under
  `C:\Program Files` was read as the path `C:\Program` and the launcher gave up
  before starting anything. The probe now writes the banner to a file and
  `findstr`s it, and nothing could have caught that shape coming back: a Linux
  runner has no `cmd.exe`, and a Windows runner's own JDK sits in
  `C:\hostedtoolcache`, a path with no space in it to strip quotes around.
- `scripts/smoke-windows-launcher.ps1` boots the packaged full jar through
  `start.bat --no-build` and asserts what the launcher printed: `[ok] JDK
  <major> -> <the JDK under test>`, the port free, `[ok] Service is up`, `Engine
  available` (a live `/health` read — the Windows half of what
  `scripts/smoke-jar.sh` asserts on Linux) and `[ok] Stopped`. It reads the jar
  name back out of `start.bat` rather than repeating it, so it cannot assert
  against a jar the launcher would not find; it asserts the resolved JDK is the
  one under test, so a silent fall back to another JDK fails instead of passing
  vacuously; and it closes the launcher's stdin, because `pause` otherwise waits
  for a keypress no CI job can send. Anything left listening on the port is
  killed even when an assertion fails after the boot.
- It refuses to run at all against a JDK whose path has no space (exit 2): on
  such a machine the check would pass without covering the quoting path at all,
  and a green run would mean nothing. Also exit 2 with no packaged jar, or with
  only the `-slim` one — that profile keeps linux/x86_64 natives only, so on
  Windows it boots and then reports the engine unavailable by design.
- CI: a *Windows launcher (full jar)* job on `windows-latest` installs Temurin
  21 under `C:\Program Files` with Chocolatey — the JDK version this launcher
  used to reject, installed the way `winget` installs it locally — packages the
  full jar with the same Maven command the packaging job runs, and boots it
  through the launcher. The README gate table lists the new job and the PRD
  moved to 14 jobs; the *Project boundaries* gate checks both sides.

### Changed — Session ownership moved into a filter, and a new session route cannot skip it

- Ownership used to be a `UserSessionGuard.require` call inside each of the six
  session-scoped handlers. That shape has the failure mode the admin key already
  had: a new session-scoped endpoint whose author forgets the call is simply
  open, and nothing notices — the omission is invisible to every test, because
  no route is missing a check that a *list* would have said it needed.
  `SessionOwnershipFilter` now decides for every route in the new
  `RouteInventory.SESSION_SCOPED` list — `GET /api/v1/sessions/{id}`,
  `POST …/close`, `POST …/frames`, `POST …/challenges/validate`,
  `POST …/active-liveness`, `GET …/audit` — before the handler runs, so there is
  no call site left to omit.
- `ApiRouteInventoryTest` fails a route that carries a `{sessionId}` without
  being in that list, a listed route that is no longer declared or no longer
  matched (the id has to be read off the path), and a session-scoped route that
  also lands in `ADMIN_KEYED`. A session endpoint added without the opt-in is a
  red test now instead of an unguarded route.
- The 403 is written by the filter, so it no longer travels through
  `GlobalExceptionHandler`, which resolves exceptions Spring MVC raised.
  `FilterRefusal` holds the one copy of that body
  (`error`/`message`/`status`/`timestamp`, loopback CORS headers included) and
  `AdminKeyFilter` and `RateLimitFilter` write through it too: three filters
  produce a shape the client guide documents and clients parse `message` out of,
  so a second copy of it was a contract change waiting to happen.
  `UserSessionGuard.refusal` returns the reason — the text that becomes
  `message` — instead of throwing a `ResponseStatusException`, and the
  handler-side `require` call is gone.
- Nothing a client sees changed: the same 403 with the same two messages, the
  same CORS treatment on the refusal, an unknown session id still answered by the
  handler (404, or the audit route's empty trail), a malformed id still a 400
  rather than a 500, and a browser's `OPTIONS` preflight still passing because it
  cannot carry the claim.
- Tests: `SessionOwnershipFilterTest` (10) drives the filter directly — the
  refusal body, the chain actually stopping, the shapes it must not refuse
  (anonymous session, unknown id, malformed id without a lookup, preflight) and
  that it does not widen the CORS origin list; `UserSessionGuardTest` now asserts
  the returned reasons, `ApiRouteInventoryTest` +3 pins the opt-in, the matcher
  and the non-session cases, and `HealthControllerTest` imports `TestConfig`
  because a slice builds every filter bean. Verified by falsification: adding an
  unlisted `GET /api/v1/sessions/{id}/ping` to `SessionsController` fails
  `everySessionScopedRouteOptsIntoOwnership` naming the route, and the probe was
  removed afterwards.

### Fixed — The documented `onRepeatedFailure` behaviour is now the actual behaviour

- `docs/configuration.md` (and the DTOs it mirrors) documented three terminal
  outcomes for a spent retry budget — `locked` / `escalate_to_operator` /
  `failed`, each with its own `failureReason` and `mayRetrySession` — and said
  they were "wired into the HTTP decision path, so a per-workflow value is real
  behaviour, not just stored config". They were not: the engine answered every
  spent budget with `reject` + `max_retries_exceeded`, so a per-workflow
  `onRepeatedFailure` was stored config, `mayRetrySession` was documented on four
  documents and set by nothing, and the client layer carried a workaround for the
  gap (`ServiceLivenessEventMapper` derived the outcome from the policy name
  because the action was always `reject`).
- Each `RepeatedFailureAction` now carries its own outcome — action,
  `failureReason`, retryability and the generic user-facing message — and the
  engine reads it when the last retry is spent, so `ESCALATE_TO_OPERATOR`
  escalates, `LOCK_OUT` locks and `FALLBACK` (= the seeded `ALLOW_RETRY`) fails
  with `mayRetrySession: true`. Every reason keeps the
  `max_retries_exceeded` prefix, so a client that only knows the old value still
  recognises the outcome.
- `ChallengeValidationResult` carries `mayRetrySession` (set only on a terminal
  budget-exhausted verdict), `FrameProcessResult` no longer advertises a field
  no code path could set (the frame path never spends the retry budget, so it
  cannot emit the terminal vocabulary), and `docs/PRD.md` now lists the two
  response shapes accordingly.
- The console treats the three outcomes as terminal and shows the server's own
  message: `LOCKED OUT` (not `REJECT`) with no "new session" hint for a lock-out,
  `ESCALATE TO OPERATOR`, and `FAILED` with the retry hint for a fallback. The
  passive loop now also stops on any non-`retry_passive` verdict instead of
  looping a closed session.
- The service-mediated client mapped the three actions only on **frame**
  responses, where they can never appear — `ServiceLivenessEventMapper.fromValidation`
  fell through to "verifying", so an overlay would keep waiting on a session the
  service had already closed. The terminal vocabulary now ends a validation too,
  and the attempt that spent the last retry counts as consumed (it was made).
- Tests: `DecisionEngineServiceTest` +3 (one per repeated-failure action, each
  pinning action, `failureReason`, retryability and that no further challenge is
  issued), `ServiceLivenessEventMapperTest` +1 (the three actions end a
  validation with the right next action, and each consumes the attempt) and the
  console DOM suite gained a budget-exhausted scenario asserting the headline,
  the server's message, the retry hint only for `failed`, and that the resolved
  challenge is never re-submitted.

### Fixed — The Python backend speaks the same repeated-failure vocabulary

- `pad_liveness_backend` answered every spent retry budget with `reject` and a
  reason of its own (`max_retries_exceeded` / `_locked` / `_escalated`), so a
  client written against the Java contract — or against the table in
  `docs/configuration.md` — could not tell a lock-out from an escalation, and the
  per-workflow `on_repeated_failure` was stored config there too. Each policy now
  carries the outcome it produces (`FailurePolicy.outcome` in
  `app/models/enums.py`, mirroring the Spring service's `RepeatedFailureAction`):
  `lock` → `locked` + `max_retries_exceeded:locked_out`, `escalate` →
  `escalate_to_operator` + `max_retries_exceeded:escalation_required`,
  `allow_retry` → `failed` + `max_retries_exceeded`, with the same generic
  user-facing messages and the same `max_retries_exceeded` prefix a client that
  only knows the older spelling still recognises.
- `ChallengeValidationResult` gained `may_retry_session`, set only on the terminal
  budget-exhausted verdict, and the action's documented domain now names all three
  terminal actions. Nothing else in the service keyed off the old reasons.
- Tests: +4 in `tests/test_liveness_api.py` — each policy driven through the real
  API (a low-score frame issues a challenge, the failing frame stream then spends
  three retries), asserting the terminal action, `failure_reason`,
  `may_retry_session`, the message, that only the last verdict is terminal, the
  audit trail, and that the closed session refuses further frames; plus the
  mapping table itself with its prefix invariant. The divergence had **no**
  coverage before — no Python test reached retry exhaustion at all. Restoring the
  old `reject` mapping fails the three API cases.
- Docs: the backend README tables the shared vocabulary, `docs/configuration.md`
  points at it, and `docs/PRD.md`'s companion-project and test-suite counts move
  to 42 tests.

### Added — One contract test, both backends

- Each implementation was only ever asserted against its own expectations, which
  is exactly why the Python backend could answer a spent retry budget with
  `reject` while the Java service answered `locked` (fixed above) without either
  suite noticing. `CrossBackendContractTest` drives one scenario on both — the
  Spring service over real HTTP (real OpenCV, real scorer, a 3s challenge window)
  and the FastAPI backend through `pad_liveness_backend/tests/cross_backend_scenario.py`
  over a subprocess, because it is a different runtime — and compares the terminal
  verdicts field by field: `action`, `failure_reason`, `may_retry_session`, the
  user-facing message, the session and challenge status, and the retry that
  precedes the outcome.
- Each side is read under its own documented spelling (camelCase here, snake_case
  there) and the spellings are asserted, not papered over by a lookup that accepts
  both, so a rename on either side fails. Enum casing (`FAILED` / `failed`) is the
  one deliberate difference the comparison normalises, and it says so.
- Tests: the scenario script is covered by the Python suite as well
  (`test_cross_backend_scenario.py`, +4: the documented shape for all three
  policies, plus a larger budget to show the outcome does not depend on its size),
  and the new Java test was verified by mutation — restoring the old Python
  vocabulary fails it with `expected: <locked> but was: <reject>`.
- CI: a new *Cross-backend contract* job sets up JDK 17 and Python 3.12, installs
  the backend's test dependencies and runs the test with
  `PAD_LIVENESS_REQUIRE_PYTHON=1`, so a runner without the Python environment fails
  the job instead of skipping the comparison inside it. The unit matrix excludes
  the class (it would only skip there, since that job installs no Python) and the
  README gate table lists the new job, which the boundaries gate checks from both
  sides.

### Fixed — Session ownership (`X-Subject-Ref`) is enforced, not just documented

- `UserSessionGuard` implemented the resource-level ownership rule — a session
  created with a `subjectRef` may only be acted on by a caller presenting it —
  and had a unit test, but **no route called it**, so an owned session was
  reachable by anyone who could reach the port. It is now invoked on every
  session-scoped endpoint: `GET /api/v1/sessions/{id}`,
  `POST …/close`, `POST …/frames`, `POST …/challenges/validate`,
  `POST …/active-liveness` and `GET …/audit`. A missing or mismatched header is
  403 and the refusal happens before any frame is decoded or scored.
- Anonymous sessions (no `subjectRef`) stay reachable without a header, which is
  what the console and the shipped `LivenessHttpClient` create — their behaviour
  is unchanged. `X-Subject-Ref` is allowed in the CORS headers so a browser host
  that creates an owned session can send it, and the header is documented in
  `docs/client-integration-guide.md` (§5.5) and `docs/brain.md`.
- Tests: +1 to each of `SessionsControllerTest` (read requires the header, close
  refuses another subject, anonymous sessions stay open), `FramesControllerTest`,
  `ChallengesControllerTest`, `ActiveLivenessControllerTest` and
  `AuditControllerTest`, each asserting the 403 and that no work was performed on
  the refused call.

### Added — `LivenessHttpClient` can create and drive an owned session

- The shipped client could only create **anonymous** sessions: `createSession` had
  no subject reference, so a host could not use the `X-Subject-Ref` ownership the
  service enforces. `LivenessClient` now declares
  `createSession(workflowType, deviceId, subjectRef)` — the two-argument form is a
  `default` delegating with no owner, so anonymous hosts and implementations are
  untouched — and `LivenessHttpClient` puts the reference in the create body,
  remembers it for that session, and repeats it in the `X-Subject-Ref` header on
  every session-scoped call it makes (`frames`, `challenges/validate`, `close`).
  The owner is released by `closeSession`, which bounds that bookkeeping by
  sessions in flight rather than by sessions ever created.
- A 4xx response used to be parsed as if it were the success shape, so the guard's
  refusal — an error object with no `id` and no `action` — failed as a
  `NullPointerException` inside a parser, or silently defaulted a field. Every call
  now goes through one transport helper that raises a `LivenessClientException`
  carrying the status and the service's own `message`, so a mismatched subject
  reference reads as a refusal. `isLivenessEnabled` keeps its documented fail-open
  default (never bypass liveness because the config answer was unreadable).
- Tests: `LivenessHttpClientWireTest` (7) drives the client over a real socket
  against a stub `HttpServer` — the only way to see the request body and headers —
  asserting the create body claims the owner, that every session-scoped call
  repeats it under the name `UserSessionGuard.HEADER` (the client declares the
  literal itself to stay free of the web layer, so this pins the two spellings
  together), that `close` releases it, that an anonymous session still sends no
  header, that a 403 arrives as a `LivenessClientException` rather than a parse
  failure, and that an unreadable config answer is not mistaken for `livenessEnabled:
  false`. Suppressing the header in the client fails two of the seven.

### Added — Secret scan gate

- `scripts/check-secrets.sh` runs gitleaks over the checkout **and its Git
  history**, so a credential that was committed and then removed still fails
  until the history is rewritten; `--redact` keeps the value out of CI logs and
  terminal scrollback. The image tag is pinned so a new gitleaks release cannot
  change what the gate accepts on its own schedule, and the CLI is used when one
  is on `PATH` (faster locally). Wired as the *Secret scan* CI job
  (`fetch-depth: 0`, since a shallow checkout would scan one commit) and listed
  in the README's gate table, which the *Project boundaries* gate checks from
  both sides.

### Changed — Dead code and stale artefacts removed

- 43 unused Java imports across 23 files, 7 unreferenced MediaPipe landmark
  constants (the right-iris ring and the unused lip/model-point tables), an
  unused `@Mock` field in `ApcerEndpointTest`, and the unreachable
  `FrameProcessResult.mayRetrySession` field.
- Deleted `docs/brain.md.backup` (a tracked, stale copy of `brain.md`) and
  `src/test/java/io/mosip/liveness/client/LivenessHttpClientTest.java.bak`, a
  scratch copy of a test that asserted a client API (`subjectRef` in
  `createSession`) which did not exist at the time and could not compile — that API
  now does exist, and is covered by `LivenessHttpClientWireTest` (above).

### Added — Manually requested active liveness (console extra check)

- Above the passive threshold a session passes and the engine issues no
  challenge — that part is right, but it left no way to ask for one anyway.
  Added `POST /api/v1/sessions/{sessionId}/active-liveness`: on a session that
  already passed (`status = PASSED`, `finalResult = true`) it issues a single
  active challenge marked `manual` and hands it back, and the console's new
  **Run active liveness** button drives it. Refusals are fail-closed 409s (not
  passed yet, active liveness disabled, no challenge types configured), and a
  repeat call while a manual challenge is still `ISSUED` returns that same
  challenge instead of issuing a second one.
- The extra check is *advisory*: its result is stored on the challenge row and
  audited (`CHALLENGE_PASSED` / `CHALLENGE_FAILED` with `manual: true`, PAD hits
  with `stage: active_manual`), but the session's verdict is never rewritten — a
  missed action leaves the session PASSED and consumes no retry budget. The
  engine's own escalation path is untouched, so an automatic challenge still
  decides its session exactly as before.
- Schema: `challenges.manual BOOLEAN NOT NULL DEFAULT FALSE`
  (`V11__challenge_manual_flag.sql`), so every pre-existing challenge stays
  engine-issued. The route is client-facing (no admin key) and shares the
  per-session rate-limit budget with frames and challenge validation.
- Tests: `DecisionEngineServiceTest` +6 (refused before a pass and when active
  liveness is disabled, issued and marked `manual`, idempotent while open, a
  passed and a missed extra check both leaving the verdict untouched, a PAD hit
  audited as `active_manual`), `ActiveLivenessControllerTest` (3 — 404, the
  created manual challenge, structured 409), `ChallengesControllerTest` +2 (a
  manual challenge accepted on a PASSED session, an engine challenge still
  refused there), `RateLimitFilterTest` +1 (the extra check shares the session
  budget), and the console DOM guard suite drives the button end to end
  (disabled until a pass, requests the challenge, shows that check's own
  verdict). The suite's challenge stub now carries the resolved challenge like
  the server does — without that shape the loop's `retry_challenge` fallback
  re-submitted a just-failed manual challenge and surfaced a 409 in the verdict
  box (caught by driving the real page, fixed by treating a manual verdict as
  terminal for its challenge).

### Fixed — Passive liveness score pinned at `1.000` on genuine faces

- The MiniFASNet-V2 live-class probability is over-confident: on an ordinary
  genuine webcam face it sits at 0.99–0.9999, so the console's decision log
  showed `score=1.000` on every frame and the number carried no information.
  Added `ProbabilityCalibration` — a log-odds temperature anchored at the
  default threshold — and wired it through `PassiveScoringService` /
  `OnnxMiniFasNetBackend` as `mosip.liveness.score-temperature` (default
  `4.0`). The map is continuous, strictly increasing and a fixed point at
  `0.80`, so every pass/fail verdict at `t <= 0.80` is unchanged while the
  genuine tail spreads into a readable band (the committed genuine fixture goes
  `0.99499` → `0.9139`, the print fixture's live probability stays `0.00055`).
  A per-workflow threshold above 0.80 is applied to the calibrated scale, so it
  demands a slightly higher raw confidence — the existing "recalibrate after a
  scorer change" rule applies. Set the key to `1` to report the raw model
  confidence. The PAD verdict deliberately keeps the raw probabilities (it
  decides by class ranking, which a monotonic temperature cannot change), so
  attack rejection — and the audit trail's PAD confidences — are untouched.
- Tests: `ProbabilityCalibrationTest` (10 — identity, below/at-pivot
  pass-through, threshold-verdict preservation, monotonicity, the spread of a
  saturated genuine score, attack passthrough, NaN, and constructor
  validation) and two `OnnxMiniFasNetBackendTest` cases pinning the calibrated
  genuine score above the threshold while staying below the raw one, plus a
  malformed-temperature configuration error.

### Added — One backend-selection key for both wirings (interop report F5)

- `mosip.liveness.backend` now selects the backend for **both** paths behind
  one vocabulary (`LivenessBackendSelection`): the HTTP scorer
  (`PassiveScoringService`) and the `LivenessBackend` SPI bean
  (`AppConfig.livenessBackend()`, consumed by `FaceLivenessEngine`). The
  default `auto` — and `heuristic` — keep each path byte-for-byte on today's
  behaviour (service: ONNX→heuristic; SPI bean: the scripted mock), so the
  key changes nothing until a deployment opts in. Explicit ids (`mock`,
  `onnx-minifasnet-v2`, `mediapipe-facemesh`, `tflite-minifasnet`) move both
  paths together, named by the same audit ids the interop contract pins.
- Explicit means strict: an id that cannot load fails closed with a coded
  `LivenessException` naming the key (service: cached, rethrown, never a
  silent swap to the heuristic; engine: the existing
  `DEVICE_CONNECTION_FAILURE` mapping), and an unknown value fails at bean
  construction — a typo must stop startup, not quietly pick another scorer
  (the divergence F5 is about). `model-path` applies to ONNX selections;
  construction loads nothing at boot (the engine calls `initialize()`).
- Tests: `LivenessBackendSelectionTest` (5 — vocabulary, fail-fast, SPI
  factory by id, mock default, the Spring bean mapping the same key),
  `PassiveScoringServiceTest` +4 (explicit mock/ONNX honoured, unavailable
  MediaPipe refuses closed with the key in the message — availability probed,
  not assumed — unknown mode fails at construction), and the interop suite +1
  constructing the engine **through the selection path** for every selectable
  id (the report's own recommendation on F5). F5 and recommendation 3 are
  marked resolved in the report; configuration docs and the yml comments
  document the shared semantics.

### Added — Measured per-frame latency and throughput for the mock and ONNX backends

- `BackendPerFrameLatencyTest` measures what one SPI conversation costs at
  the seam: the identical genuine-face fixture (256×320) through
  `analyzeFrame → assessPad → scorePassiveLiveness` in engine order,
  single-threaded, decode excluded, 10 warm-up frames then 100 measured
  frames per backend. Results land in
  `target/backend-latency-results.txt` and in the interoperability report's
  new section 10. Assertions are sanity-level only (every frame scored in
  `[0,1]`, positive latency, ≥ 1 fps floor) — timing is host-dependent, so
  the test guards the measurement, not a wall-clock target.
- **Numbers (one run, Linux x86_64, 4 × i3-1005G1, OpenJDK 17.0.20.1, ORT
  1.24.2 CPU):** mock **0.017 ms/frame mean** (p95 0.037, ≈59,515 fps);
  ONNX **156.768 ms/frame mean** (analyze 46.680 · pad 54.386 · score
  55.702; p50 148.039, p95 206.396, max 309.050; **6.4 fps**), genuine face
  scoring `[0.9950, 0.9950]` on every frame.
- The phase costs confirm at the seam what the HTTP stage timers found
  end-to-end: `scorePassiveLiveness` and `assessPad` each call
  `probabilitiesFor(frame)` independently, so one qualifying frame pays
  three Haar cascades and two inferences — and 6.4 fps single-threaded sits
  under the 10–15 fps analysis budget, so camera rate needs that duplication
  removed or scoring parallelised. The report gains the data (section 10),
  finding **F7**, and an amended limitation 4 (seam timing now measured;
  end-to-end/device timing stays out of scope).

### Added — MODEL_UPDATED audit events from the signed-manifest store

- `SignedManifestModelStore` now audits what spec §10 defines
  (`MODEL_UPDATED: old/new version, hash ok`) through an `AuditLogger`
  (no-op on the existing constructors, so every current caller is
  unchanged): each `activate` call and each `rollback` emits exactly one
  event. A swap carries `action=SWAP`, the old version (`none` for a first
  install), the new version and `hashOk=true`. A refusal carries
  `action=ROLLBACK` with `oldVersion == newVersion` (the previous model is
  kept) and a `reason` naming the failing check — `INPUT`, `SIGNATURE`,
  `HASH`, `MIN_APP_VERSION` or `UNSIGNED` — while `hashOk` stays
  independent of it: a payload whose digest matches but whose signature does
  not is recorded as `hashOk=true, reason=SIGNATURE`, a tampered payload as
  `hashOk=false, reason=HASH`. `rollback()` emits spec §13's
  `MODEL_UPDATED(rollback)` — `reason=HEALTH_CHECK`, the undone and restored
  versions, `hashOk=true` (both states were digest-verified when swapped in:
  what failed is the health check, not the hash).
- Events carry no session or workflow — a model update is not a liveness
  decision — and are emitted *before* any state change, so an audit sink
  that throws aborts the transition fail-closed instead of leaving a swap
  the trail never saw. The unsigned bare-interface path is audited too
  (`reason=UNSIGNED`) instead of silently returning false, and
  `AndroidModelStore` gains audit-forwarding constructors. The old "the
  caller audits MODEL_UPDATED(rollback)" contract in `ModelStore` now notes
  that audited stores discharge it themselves, so callers must not log it a
  second time.
- `SignedManifestModelStoreAuditTest` (9 tests) pins every field above plus
  the no-session/no-workflow shape and exactly-one-event-per-call;
  red-proved by forcing the emitted `hashOk` to `true`, which failed exactly
  the two refusal-field assertions while every decision stayed correct.

### Added — Key ids on signed model manifests (signing-key rotation)

- `SignedModelManifest` carries a `keyId` — the SHA-256 hex of the signing
  key's X.509 encoding, derivable from either half of the RSA pair and
  externally via `openssl pkey -pubin -in vendor-public.pem -outform DER |
  sha256sum`. `sign(...)` stamps it automatically; the canonical payload
  stays spec §13's four fields, so pre-keyid manifests verify
  byte-identically and old readers (which never load the property) keep
  verifying new ones. The id sits outside the signature but selects the
  verifying key, and `verifySignature` additionally requires a named id to
  match the key it is checked under — a rewritten id cannot hop to another
  trusted key.
- `SignedManifestModelStore` now verifies against a key *ring*:
  `new SignedManifestModelStore(appVersion, previousKey, currentKey)` covers
  the rotation window; an id present is authoritative (exactly that key or
  refuse — never a fallback), a blank id is a legacy manifest verified under
  any key still trusted, and revoking a key means constructing without it
  (everything it signed is refused, kid-less manifests included). The
  single-key constructors keep their exact behaviour as a ring of one, an
  empty ring throws at construction, and `AndroidModelStore` gains the same
  rotation constructor while reading the `keyId` property.
- The manifest tool writes the `keyId` line (always — same lesson as
  `minAppVersion`: a *missing* key loads as null) and self-checks it on
  reload. `SignedManifestRotationTest` (7 tests) pins derivation, the
  keyring, legacy fallback, revocation, id-hop and unknown-id refusal, and
  the construction guards; `ModelManifestToolTest` (now 10) proves rotation
  end-to-end through the written file plus a stripped pre-keyid file still
  installing. All pre-existing manifest tests pass unmodified — the
  five-field constructor and kid-less verification are unchanged.

### Added — Model-manifest generator tool (`ModelManifestTool`)

- `io.mosip.liveness.tools.ModelManifestTool` produces the vendor-signed
  properties manifest `AndroidModelStore.install(...)` reads (spec §13), so
  deployments can package a model release instead of hand-rolling the format:
  `sign` hashes the artifact with the same SHA-256 the store re-derives at
  activation, signs `modelId|version|sha256|minAppVersion` (SHA256withRSA) and
  writes the five keys deterministically (ASCII-escaped, so a hostile
  modelId/version can't forge a `key=value` line or a comment); `keygen`
  bootstraps a 2048-bit PKCS#8 vendor keypair, refusing to overwrite an
  existing key or emit a sub-2048 one. Legacy PKCS#1 PEM keys (`openssl
  genrsa`) are wrapped and accepted.
- The tool fails closed: usage errors exit 2, operational errors exit 1 with
  no manifest left behind — and before returning, `sign` re-reads the bytes it
  wrote and verifies them under the signing key, so an escaping or I/O defect
  fails the command instead of shipping a file the device would refuse.
  `ModelManifestToolTest` (8 tests) round-trips through the client's own
  `Properties.load` path and the engine's `SignedManifestModelStore`: install,
  tampered payload, rewritten field, hostile values, the `minAppVersion` gate,
  a PKCS#1 key, and every failure exit.

### Added — The desktop overlay now renders the service-mediated path too

- `DesktopLivenessAdapter` (the REST/session path, as opposed to the on-device
  orchestrator) emits the same `LivenessStateEvent` / `LivenessFinalResult` pair
  the  orchestrator emits, so one overlay renders both: point the adapter's listener
  at the overlay's `asListener()`, and the desktop client no longer needs the
  orchestrator in process. The pre-existing raw-DTO callback surface is untouched
  and still fires alongside the listener.
- `ServiceLivenessEventMapper` is the whole translation, as a pure function of
  one response plus the session's frozen policy and the client's counters —
  framing, challenge prompts, the challenge counter, attempt failures, the
  terminal vocabulary, transport failures and the host's cancel all map without
  a network, a clock or a node. Two rules carry over from the orchestrator: the
  client renders catalogue keys and never the service's English `message`, and
  it counts attempts where the budget lives (so a spent budget renders recovery
  guidance with no retry).
- The client now reads two things the service already returned and the client
  threw away: the policy frozen on the session at creation (`SessionInfo.policy`
  — minChallengeCount, maxRetries, onRepeatedFailure) and the follow-up challenge
  issued by a validation response. Without the policy the overlay cannot draw
  "Challenge 1 / N", and without the follow-up it cannot prompt the next
  challenge. The response parsers are static and pinned by JSON literals
  (`LivenessHttpClientParseTest`, 8 tests), including both id shapes the service
  uses for a challenge (`challengeId` on frames, `id` on validations).
- Details that only show up in a live session: a re-served challenge does not
  advance the counter (the service answers every frame with the same
  `escalate_to_active` while a challenge is open); a `reject` is retryable with
  Retry restarting the gate as a fresh session, while recovery guidance comes
  from the budget-exhausted vocabulary or a `reject` whose closed session reports
  `max_retries_exceeded`; and transport failures are split by entry point —
  `startSession` propagates, `onFrame`/`submitChallenge` render the recoverable
  `DEVICE_ERROR` and keep the session open, because frames arrive from the
  streamer's decode loop where throwing would kill it.
- Tests: `ServiceMediatedOverlayTest` (7) drives the adapter from off the FX
  thread — as the host's Streamer loop does — into the real overlay and asserts
  the rendered cards; `ServiceLivenessEventMapperTest` (22) pins the mapping
  table. The overlay tests now share one toolkit harness (`HeadlessFx`), since a
  test JVM can only start JavaFX once. Suite: 488 tests, 0 failures, exit 0.

### Added — Headless JavaFX render smoke test, and the two overlay defects it found

- `LivenessChallengeOverlaySmokeTest` (10 tests) builds the real overlay — real
  `FXMLLoader`, real `liveness-overlay.css`, real layout pass — and reads the
  rendered nodes back. JavaFX is pinned to Monocle's headless glass platform in
  the surefire configuration, so no display, window system or GPU is involved
  and the CI runner runs it unchanged. Every `LivenessState` is asserted
  against the card the design's state table gives it, together with the copy,
  the CSS-resolved white text, the laid-out card bounds, the Retry / Cancel
  affordances, the red failure tone and the green PASSED oval; the LOCK_OUT
  countdown, the button callbacks and a swapped i18n catalogue are covered
  too. The state→card expectations are spelled out in the test rather than
  read from the presenter, so one wrong mapping cannot pass twice.
- Two rendering defects the test found, both fatal to a path that had never
  been executed end to end:
  - `LivenessChallengeOverlay.fxml` declared a concrete `<StackPane>` root, so
    **every** `new LivenessChallengeOverlay()` threw `LoadException: Root
    value already specified.` — `FXMLLoader` only accepts a pre-set root for
    an `<fx:root>` document and otherwise insists on building the root itself.
    The file now declares `<fx:root>` whose `type` is `javafx.scene.layout.StackPane`,
    which is what makes `setRoot(this)` + `setController(this)` work at all;
    the DOM-level FXML test asserts the fx:root form so it cannot regress.
  - Retry and Cancel rendered as blank buttons: the FXML declares bare
    `<Button>`s and the renderer only ever toggled their visibility, never
    their text. Both labels now resolve through the catalogue
    (`liveness.action.retry` / `liveness.action.cancel`), so a deployment's
    swapped catalogue reaches them.
- `openjfx-monocle` is pinned to `${javafx.version}` (17.0.10) rather than the
  `jdk-12.0.1+2` build TestFX's own documentation points at: the old build
  dies with `AbstractMethodError` on `Window._updateViewSize` the moment the
  first window is created, and a mismatch there would have been read as "the
  smoke test is flaky".
- Suite: 451 tests, 0 failures, exit 0; the reference gate stays green (the new
  test file is scanned by it).

### Added — Config-key reference gate (`mosip.liveness.*`)

- `FlywayMigrationReferenceTest` gains a third rule: the build fails when any
  owned file — doc, comment, code string or annotation — cites a
  `mosip.liveness.*` config key that `application.yml` does not declare and
  no config class declares with `@Value`. The key set is parsed from
  application.yml at test time (SnakeYAML), so renaming or removing a key
  turns the gate red wherever the old name is still cited, and adding one
  needs no gate edit. A service's own `@Value` counts as a citation rather
  than a definition, which keeps every backend key declared in
  application.yml or a config class.
- The `mosip.liveness.android.*` build-flag namespace is discovered from its
  declaration site instead: quoted key constants in the Android gate-policy
  package. Worth knowing: `mosip.liveness.android.allow-disable` is declared
  (`LivenessGatePolicy.BUILD_FLAG_ALLOW_DISABLE`) and cited by
  `docs/resource-compliance.md`, but nothing in this repository reads it and
  `android_client/` never references it — the gate verifies declarations,
  not consumption.
- Self-validated in-file with a concatenated dangling key, and red-proved end
  to end: a planted undefined-key citation turns the scan red with the
  offending line and the full defined-key list; removal turns it green again.
  Gate: 4 tests, 0 failures.

### Added — Spec-reference gate: checked-in specs + §-anchor rule

- `docs/references/` now carries the two liveness specs the repository cites —
  `orchestration.auth` (state machine, sequence diagrams, adapter contracts)
  and `analyse.md` (requirements analysis) — so section citations resolve
  against a checked-in source of truth instead of a document nobody has.
- `FlywayMigrationReferenceTest` gains a third rule beside the migration ones:
  every cited spec section anchor must match a heading of the checked-in
  specs. File-qualified citations (`orchestration.auth §5.2`,
  `analyse.md §5.4`) are validated against that spec's own heading set,
  extracted from the file at test time; bare anchors (`spec §4`, and every
  `§N` in `android_client/*.md` markdown) are validated against the union of
  both specs. Doc-internal anchors — citations of this repository's own
  design, guide and resource-compliance documents — are deliberately out of
  scope. The rule is self-validated in-file with concatenated dangling tokens
  and was red-proved end to end: a planted dangling citation turns the scan
  red with the offending line and the known heading list, removing it turns
  the gate green again.

### Added — Backend interoperability report (mock / ONNX / MediaPipe)

- `docs/backend-interoperability-report.md` — formal comparison of the three
  `LivenessBackend` implementations across the full test suite (measured
  baseline: 439 tests, 0 failures, exit 0): SPI profiles, an observed
  conformance matrix, per-backend suite attribution, six findings (uniform
  fail-closed init mapping to `DEVICE_CONNECTION_FAILURE`; the engine's
  face-count gate absorbing backend convention drift; PAD fidelity *not*
  interchangeable though the seam is; the two wiring paths that must stay
  aligned) and honest limitations.
- New `LivenessBackendInteroperabilityTest` (12 tests) executes one identical
  parameterized contract against all three backends: audit-safe stable and
  distinct ids (pinned to the report's verbatim values), clean-start-or-coded-
  refusal initialization, the full SPI conversation (scores and PAD confidence
  in `[0,1]`, no fabricated attacks on no-face frames, idempotent shutdown),
  and the engine's accept-iff-available constructor contract — MediaPipe's
  missing-TFLite refusal lands on `DEVICE_CONNECTION_FAILURE` like any absent
  device. ONNX is additionally driven through `FaceLivenessEngine` end-to-end
  on the genuine-face fixture. Availability is probed, never assumed, so the
  same contract holds on a host where TFLite is installed.

### Added — Diagnostic opt-in mode with local-only debug panel

- Closes the acceptance audit's last 🟡 for diagnostic mode ("opt-in raw-score
  UI mode not fully surfaced"). `mosip.liveness.diagnostics-enabled`
  (default off, `LIVENESS_DIAGNOSTICS_ENABLED`) turns on a fail-closed
  collector that retains exactly what orchestration spec §10 allows — raw
  passive scores, per-frame timings, FPS and the scorer delegate — in a
  bounded 120-frame ring with a sliding 5 s FPS window and an injected clock.
  Disabled, nothing is retained at all: an opt-in that silently buffered
  would be indistinguishable from always-on.
- `GET /api/v1/diagnostics` is the panel's read side with two independent
  fail-closed gates: opted in **and** loopback (judged through
  `ClientIpResolver`, so a local reverse proxy cannot launder a remote caller
  into `local`). Both failures return the same empty 404 — remote probes learn
  neither the mode's existence nor its state — and 200s are `no-store`.
- The test console gains the debug panel: hidden until the endpoint answers
  200, polling only while visible, showing delegate, FPS, last/median raw
  scores, avg/max frame ms and the per-frame table. No pixels anywhere: the
  input is the decision DTO, and a test serialises the snapshot to prove no
  frame-shaped payload can appear (spec §10 "still no pixels").
- 15 new tests: collector aggregation/window/ring/no-pixels
  (`DiagnosticsServiceTest`), endpoint gating (`DiagnosticsControllerTest`),
  loopback judgement incl. trusted-proxy chains (`ClientIpResolverTest`), and
  the frames hook (`FramesControllerTest`).

### Added — Desktop JavaFX LivenessChallengeOverlay (ui-ux-design.md)

- `client/LivenessChallengeOverlay` + `fxml/LivenessChallengeOverlay.fxml`
  (with `liveness-overlay.css`) implement the Desktop surface the design doc
  pointed at a stub for: preview slot (JavaFX ImageView path), oval
  positioning guide, and status / challenge / failure cards driven by the
  shared orchestrator events (`LivenessStateEvent` / `LivenessFinalResult`
  via `overlay.asListener()`) — challenge index "Challenge 1 / N", normalised
  progress (indeterminate until the combined score is known), the live
  feedback cycle continue → detected → hold, context-sensitive Retry (only
  `ATTEMPT_FAILED` / `RETRY_WAIT` / recoverable `DEVICE_ERROR`, never on
  terminal failure) + Cancel, the green PASSED oval accent, and the LOCK_OUT
  "Try again in Ns" countdown (design §1/§3/§4 state table).
- Every decision lives in the pure `LivenessOverlayPresenter`, verified by 27
  headless tests that walk each row of the design's state table, plus 3 FXML
  contract tests (`fx:id` ↔ `@FXML` parity, card structure, stylesheet) — the
  suite never needs a JavaFX toolkit. The default catalogue mirrors the
  Flutter `LivenessView.defaultMessages` string-for-string and a test parses
  the Dart source, so Desktop and Android cannot drift apart (design §6: same
  states, same keys on every surface); Desktop adds only the documented
  action/template keys (`liveness.action.*`, `liveness.challenge.index`,
  `liveness.lockout.retry_in`, `liveness.cancelled`,
  `liveness.device.disconnected`).
- JavaFX enters the build as `provided` scope and is explicitly excluded from
  both the full and `-slim` service jars (verified by inspecting the
  repackage output): the overlay runs inside the MOSIP Registration Client
  host, never in the backend.

### Added — Signed-manifest model activation (Android ModelStore glue)

- `SignedModelManifest` + `SignedManifestModelStore` close the gap the
  `ModelStore` javadoc promised ("sha256 + signature at the glue layer") but
  no implementation shipped: activation now requires a spec §13 manifest —
  `{modelId, version, sha256, minAppVersion}` under a SHA256withRSA vendor
  signature — whose digest binds to the exact payload bytes, followed by an
  atomic swap that keeps the previous version for `rollback()` after a failed
  health check. The bare unsigned `activate(modelId, version, payload)` path
  fails closed (returns false), and a configured app version enforces the
  manifest's `minAppVersion` gate (malformed versions refused). Without the
  signature a stored sha256 proved nothing: whoever can rewrite the model
  file can rewrite its recorded hash — the vendor signature anchors it.
- `android_client` gains `AndroidModelStore`: file I/O glue that loads a
  properties-format manifest + payload and delegates all verification to the
  engine store (security logic stays pure-JVM, so it is engine-tested).
- `SignedManifestModelStoreTest` — 10 tamper tests: mutated model bytes
  (previous model kept), manifest digest rewritten to match tampered bytes,
  mutated modelId/version/minAppVersion fields, flipped/odd-length/empty
  signature hex, wrong signing key, unsigned/null/empty/blank refusals, the
  min-app-version gate (newer → refused, malformed → refused, unconfigured →
  no gate), and spec §13 rollback.

### Added — Per-PAI-species APCER reporting (eval)

- `ScenarioReport` now carries a **per-species APCER breakdown**:
  `apcerBySpecies` maps each attack `PresentationLabel` to an immutable
  `SpeciesApcer` row (presentations, accepted-as-bona-fide, that species'
  APCER); `BONA_FIDE` is excluded because its error rate is BPCER.
  `AttackScenarioHarness.report()` groups its already-labelled results into
  those rows, the aggregate `apcer` stays the presentation-weighted mean of
  the breakdown (asserted), and `toString` prints it — e.g.
  `apcerBySpecies={PRINTED_PHOTO=0.0167 (1/60), SCREEN_REPLAY=0.0000 (0/60),
  VIDEO_REPLAY=0.0333 (2/60)}`.
- Closes the ISO/IEC 30107-3 gap in `docs/resource-compliance.md` §02
  (APCER was aggregate-only) — tracker G1 done; now 30 ✅ · 8 🟡 · 1 🔴.
- Tests: three new cases in `AttackScenarioHarnessTest` — full-run grouping
  with weighted-mean consistency, exact hand-built per-label math (1/3 for
  one species vs a 1/5 aggregate), immutability of the map, and zero-count
  species rows rendering 0.0 rather than NaN.

### Documentation — Android RC version pins + auto-logout interplay

- `android_client/README.md` now pins the Android stack **and verifies it
  against the live RC repo** (`mosip/android-registration-client`, `main`
  v1.1.1): Flutter 3.10.4 / Dart 3.0.3 (docs Technology Stack), the
  docs-SDK-31 vs repo-`compileSdkVersion 34` drift flagged, **`pigeon:
  ^10.0.1`** (the repo's own pubspec pin, so one generator version),
  **CameraX ≥ 1.3.0** (the glue uses `ResolutionSelector`, which exists only
  from 1.3.0; an SDK-31 toolchain falls back to 1.2.x + legacy
  `setTargetResolution`), and the **Java-11-vs-17 question resolved**: 11 is
  the *backend platform* figure, the RC app builds at Java 21 with
  desugaring (so the `--release 17` engine jar links and dexes), and the
  Spring Boot service itself needs JDK 17+ regardless.
- Documents the **auto-logout ↔ liveness interplay**: worst-case gate bounds
  (≤ retries × `maxSessionDurationMs` ≈ 150 s by policy floor; typical pass
  < 10 s), the host contract (suppress the inactivity timer between
  `startSession` and `onFinal`, refresh on every `onState`, timeout floor
  above the worst case), and the fail-safe (logout anyway → view-model
  dispose → `cancelSession` → ABORTED → gate fails closed — a lost retry,
  never a bypass).
- Closes the §05 version-pinning gap (tracker G3 done) and moves auto-logout
  to partial (tracker G4 in progress, RC-side pause wiring pending) — now
  29 ✅ · 8 🟡 · 2 🔴.

### Documentation — ISO 30107-1 injection threat model

- `design.md` gains a threat-model section for **digital injection attacks**
  (virtual camera, hooked camera API, frame replay into the pipeline) — the
  class ISO/IEC 30107-1 explicitly scopes out. Written in Part-1 vocabulary
  (**PAI**, **attack presentation**, **bona fide presentation**, PAD
  subsystem), it maps each `PresentationLabel` species to its PAI class, lays
  out the layered countermeasures (signed capture binding, per-attempt
  session/nonce binding, fail-closed pipeline, session-scoped rate-limited
  frame ingestion, keyed audit chain, model integrity), and states the
  residual risk — endpoint integrity is assumed at this layer, not enforced.
  (Sequence-diagram and thread-safety sections renumbered to §7/§8; no
  cross-references pointed at them.)
- Closes two rows in `docs/resource-compliance.md` §01 — the scope-boundary
  partial and the PAI-terminology gap — and marks tracker item G2 done
  (now 28 ✅ · 7 🟡 · 4 🔴).

### Added — Measured frame-rate fallback

- `FrameRateMeter` measures the rate frames **actually arrive at** the
  frame-source boundary (a clock-injected, 5 s sliding window over a wrapped
  `FaceFrameSource.Listener`) instead of trusting the configured `maxFps`
  ceiling advertised in `SourceCapabilities`.
- The Android orchestrator consults the measured rate whenever the engine
  escalates: below 5 fps — and for any rate that cannot be proven — BLINK is
  excluded from the challenge draw, falling back to
  `SMILE` / `TURN_HEAD_LEFT` / `TURN_HEAD_RIGHT`.
  `FaceLivenessEngine.requestChallenge(sessionId, excludedTypes)` applies the
  exclusion (recording it in the `CHALLENGE_ISSUED` audit event) and never
  lets the pool go empty: excluding every allowed type falls back to the full
  pool. Closes the "3 fps minimum → adaptive fallback" gap in
  `docs/resource-compliance.md` §06 (now 26 ✅ · 8 🟡 · 5 🔴).
- Tests: `FrameRateMeterTest` (rate math, window pruning, listener wrap),
  `FrameRateFallbackTest` (a measured 4 fps stream excludes blink at issuance
  and never prompts it; a healthy burst keeps the full pool; boundary table
  incl. unmeasurable-rate-fails-closed), plus engine-level pool-exclusion and
  empty-pool-guard tests in `EngineEscalationTest`.

### Fixed — Liveness evidence binding

- `AndroidLivenessOrchestrator` now records the sha256 of each attempt's
  best-scoring frame into `LivenessEvidence.bestFrameSha256` (spec §8
  `pass()` evidence field, analyse.md §5.4 binding). The field was declared
  and signed but never assigned, so every evidence record carried a null
  hash and the downstream `consistency(bestFrame, signedCapture)` check had
  nothing to bind to. The hash resets with the nonce on every retry, so
  evidence can only ever reference frames from the attempt it certifies.
- `LivenessEvidenceBindingTest` (7 tests) proves the binding end to end: the
  evidence's `bestFrameSha256` + nonce verified against a downstream signed
  capture payload (spec §8 `bindingOk = isGateValid && consistency(...)`),
  with tampering rejected — foreign-frame capture under a valid signature,
  forged capture signature, evidence edited after signing (frame hash or
  nonce), stale-nonce replay, capture bound to a foreign session, and an
  expired gate window. The happy path recomputes the frame digest with an
  independent SHA-256 to pin the hash to actual pixel bytes.

### Added — Build hygiene

- `FlywayMigrationReferenceTest`, a self-maintaining gate that fails the suite
  when any owned file cites a Flyway migration that does not exist in
  `db/migration` — the class of drift where a comment keeps citing a migration
  the directory never had (the audit-chain javadoc and a test comment pointed
  at a number the migration set had left behind) while every compiler and test
  stays green. The gate discovers the migration set from the directory at
  runtime instead of pinning a list, recognizes exact-filename, javadoc
  code-span and prose citations, and self-validates by planting a dangling
  citation. Rides the Unit CI job unchanged.

### Added — Android liveness gate (Face Liveness + PAD orchestration)

- New `io.mosip.liveness.android` package implementing the Android
  Registration Client liveness orchestration on top of the shared engine
  pipeline, so Resident/Operator/Supervisor flows behave identically on
  Desktop and Android. The orchestrator owns the state machine
  (IDLE → INITIALIZING → POSITIONING → PASSIVE_EVALUATING → challenge states
  → PASSED / ATTEMPT_FAILED / RETRY_WAIT / TERMINAL_FAILURE / DEVICE_ERROR /
  ABORTED), attempt/retry counting, per-attempt nonces, gate validity windows,
  and signed evidence — while every frame is still scored by the same
  `FaceLivenessEngine` decision logic the REST service uses.
  Spec invariants pinned by tests: device errors and positioning hints never
  consume the retry budget; every retry gets a fresh engine session, nonce and
  challenge sequence; a PASSED gate is valid for a bounded window only
  (`isGateValid` fails closed after expiry); a missing/invalid model fails
  closed; an evidence-signing failure withholds evidence instead of handing
  out an unsigned record; PAD failures surface only the generic
  `liveness.pad.generic` key to the UI.
- `AndroidLivenessPolicyProvider` resolves per-role policy from the shared
  `WorkflowPolicyDefaults` table and then clamps to a hard security floor in
  code (threshold ≥ 0.60, retries ≤ 5, challenge window 3–20 s); disabling
  liveness via config is refused and audited unless the
  `mosip.liveness.android.allow-disable` build flag is set.
- Vendor-neutral `FaceFrameSource` SPI with a deterministic
  `MockFaceFrameSource` (spec §11 vendor independence; the same suite runs
  against mock + CameraX adapters).
- `android_client/` integration tree: Pigeon API definition
  (`pigeon/liveness.dart`), Flutter `LivenessViewModel`/`LivenessView`
  (i18n-key rendering, capture enabled only on PASSED), and the Android-only
  glue — `CameraXFaceSource` (keep-only-latest, ≤640x480 analysis),
  `AndroidKeystoreEvidenceSigner` (non-exportable RSA-2048 evidence key), and
  the `LivenessPigeonBridge` host-api sketch (frames never cross the channel —
  preview is a Flutter Texture).
- `AndroidLivenessOrchestratorTest` (13 tests) drives the full gate against
  the mock backend: passive pass, escalation to an engine-selected challenge
  (performed prompt-by-prompt, never user-chosen), PAD block, device-error
  neutrality, gate expiry, evidence signing and the policy floor.

### Added
- The config audit chain's hashes are now **keyed** (`AuditChainKey`), so a
  database writer without the active audit secret can no longer rebuild the trail: with
  `MOSIP_AUDIT_HMAC_SECRET` set, `entry_hash` becomes
  **HMAC-SHA-256** over the same canonical form instead of plain SHA-256, and
  the secret lives in the app server's environment rather than in the database.
  The canonical form is untouched, so this is a construction change, not a
  format change — which is what keeps the two modes comparable.
  Previously the chain stopped at the honest ceiling: anyone holding a database
  connection could read the stored hashes and recompute every link, producing a
  chain that verified perfectly on its own. `AuditChainTamperTest` now stages
  exactly that attack — edit a row, then recompute the entire trail publicly —
  and pins that it verifies under the fallback key yet breaks at the **first
  entry** under the real secret. Verified by mutation: ignoring the secret
  outright fails that test.
  Three decisions, each visible rather than silent:
  - **A secret under 16 characters fails at startup.** A guessable key
    produces keyed-looking hashes an attacker recomputes just as easily, so
    failing fast beats shipping a false sense of security. The exception names
    the setting to fix.
  - **No secret falls back to the original SHA-256**, so a fresh deployment
    boots with no configuration and chains written before the key existed stay
    verifiable. The fallback is *reported*, not implied:
    `GET /api/v1/config/audit/verify` gained an `hmac` field, so an operator
    reading `intact: true` can see whether that verdict came from a keyed chain
    (`hmac: true`) or a tamper-detecting one (`hmac: false`).
  - **Verification uses the same key as writing, and never falls back to
    accepting.** A different or missing secret reports every entry as broken
    at index 0 rather than quietly downgrading to a weaker check — unless the
    previous key is explicitly configured for a keyed rotation. A test pins the
    hard break with no overlap, with untouched rows still verifying under the
    original key.
- Keyed chains can verify historical rows with
  `MOSIP_AUDIT_HMAC_PREVIOUS_SECRET` while new application writes use the
  current `MOSIP_AUDIT_HMAC_SECRET`. `/api/v1/config/audit/verify` reports
  `rotationWindowOpen` (previous key configured) and `retiredKeyHashes`
  (historical entries still need it); the console shows intact, broken, and
  open-window states. The JPA round-trip test proves old and new hashes verify
  after reload. **Limit:** audit rows are immutable, so the app cannot re-key
  history; keep the previous key while any hashes need it. A trusted archival
  or re-key protocol is required to retire it.
- Every `CONFIG_CHANGED` audit entry now carries a **risk classification**
  (`details.risk`: `HIGH` or `LOW`, plus the named weakening moves).
  An edit that lowers `passiveThreshold` or switches `livenessEnabled`
  or `activeLivenessEnabled` off is flagged `HIGH`; everything else is
  `LOW`. The console's **Policy change history** panel shows the level
  on every entry and lists the reasons above the field diff, so a change
  that quietly weakens PAD reads as an alert rather than a diff to be
  hand-checked. Entries written before the classification have no `risk`
  key and render an em dash. Because the classification is computed over
  the diff and sealed inside the hashed `details` payload, it is covered
  by the tamper-evidence below.
- Config audit entries are now **immutable and tamper-evident**. Each
  `CONFIG_CHANGED` row stores `prev_hash` (the previous entry's hash, `GENESIS`
  for the first) and `entry_hash = SHA-256(prev_hash ‖ canonical(entry))`
  (`V9__audit_chain_immutability.sql`), so editing a row invalidates its own
  hash and deleting one leaves every successor pointing at a hash that no
  longer follows. Immutability is enforced by the **database**, not by Java:
  `BEFORE UPDATE` / `BEFORE DELETE` triggers that raise, because a Java-side
  rule is bypassed by anything holding a connection. Those triggers also fire
  for the `ON DELETE CASCADE` from `liveness_sessions`, so deleting a session
  that has audit rows now fails rather than silently erasing the evidence —
  nothing in the application deletes sessions, so that is now enforced instead
  of assumed.
  Three subtleties the implementation had to get right, each caught by a test
  rather than by reading:
  - `details` round-trips through a converter that rebuilds it as a plain
    `HashMap`, so hash order is **not** stable across a database round-trip.
    Hashing what came out of the map would fail every verification for reasons
    unrelated to tampering. The payload is canonicalised first — keys sorted
    recursively, every component **length-prefixed** so a value containing the
    separator cannot be re-split into different fields.
  - `id` is assigned by Hibernate at INSERT, so it is null at hash time and the
    canonical form would change the moment the row lands; it cannot be hashed
    afterwards either, because the new trigger rejects the UPDATE. `id` is
    therefore deliberately excluded — chain position plus `prev_hash` already
    binds an entry uniquely.
  - The chain is walked in `created_at` order, so two edits inside one
    millisecond would make a perfectly intact trail fail. Timestamps are
    nudged forward by 1 ms rather than left ambiguous: a false alarm on a
    security control is its own kind of bug.
  Read-head and write-head happen under one lock, so two concurrent admin
  requests cannot both chain onto the same predecessor and fork the trail.
  `GET /api/v1/config/audit/verify` walks the whole chain (never a page — a
  break past the page boundary would read as "intact") and reports the first
  break, distinguishing an **edited** row from a **deleted** one, since the
  remedy differs. Verified live: five consecutive PUTs chain and report
  `intact: true` with a head hash.
  **Ceiling, stated plainly:** this detects corruption and edits by anyone who
  does not rebuild the chain — including the admin-key holder working through
  the API, which is the realistic threat here. It does **not** stop an attacker
  with database write access, who can read the stored hashes and recompute the
  chain. Closing that means keying the hash with a secret the database does not
  hold (HMAC, secret from the environment) or anchoring the chain head where a
  database writer cannot reach. The hash is now **keyed** when
  `MOSIP_AUDIT_HMAC_SECRET` is set — see the entry below.
- `audit_logs` gained a `workflow_type` column (`V8__audit_logs_workflow_type.sql`),
  so `GET /api/v1/config/audit` can be filtered **in the database** with
  `?workflowType=`. The audit table grows one row per frame decision, so
  narrowing in the browser meant paging through the entire pipeline history to
  answer a policy question; the feed now has a purpose-built finder
  (`findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc`) served by
  a new `idx_audit_logs_config_feed(event_type, workflow_type, created_at DESC)`.
  The migration backfills existing rows twice — from `liveness_sessions` for
  pipeline events, and from the `details` JSON for session-less `CONFIG_CHANGED`
  rows, which is the only place the old history recorded the workflow — so
  nothing already logged becomes unfilterable. The column is nullable: an entry
  that cannot be resolved stays NULL rather than being guessed at.
  The value stays in `details.workflowType` as well; the column is for indexing,
  not a replacement for the audit payload. `DecisionEngineService` populates it
  too, so pipeline rows are not left NULL and any future "everything for
  OPERATOR" query covers the whole trail. `workflowType` is now a first-class
  field on `AuditLogEntry`. An unknown workflow is a **400**, so a typo cannot
  silently return the unfiltered feed and read as "this workflow has no
  history". The console's Policy change history panel gained a workflow dropdown
  that pushes the filter to the server and reloads on change.
- Fixed: the console's Operational metrics panel rendered the new
  `pipelineTimings` object as `[object Object]`, because the generic key/value
  renderer stringified it. Nested objects now render one indented line per
  phase (`decode: 21.7 ms mean · 159.7 ms max · n=12 (6.3%)`), with
  `white-space: pre-wrap` on the value cell so the lines survive.
- The rate limiter's clock is now injectable (`java.time.Clock`, via an
  `ObjectProvider` so a `@WebMvcTest` slice without `AppConfig` still gets real
  time), with a `rateLimitClock` bean in `AppConfig`. Window rollover can
  finally be tested by moving time instead of sleeping out a 60-second window:
  three new `RateLimitFilterTest` cases drive a mutable clock to prove the
  session-create window resets, the frame window resets on its own clock, and
  the one-arg constructor still uses real time.
- The integration suite no longer shares a rate-limit budget with itself.
  Every test in a class runs in one process from one address, so the production
  30 sessions/min was being consumed by the suite and failing tests with 429s
  depending on order. `@TestPropertySource` now raises both budgets
  (test-only), and `rateLimitBudget_isPublishedOnLimitedPosts` asserts the
  *configured* limit instead of the literal `"30"`/`"60"` — `RateLimitFilterTest`
  is where the real defaults stay pinned, which is the correct layering.
  **Parallel execution was attempted and reverted.** The blocker is not the
  limiter: nearly every integration test first PUTs the shared RESIDENT policy
  to stage its scenario, so concurrent methods overwrite each other's setup.
  `@Execution(CONCURRENT)` failed 3/3 runs —
  `configChange_isTraceableInTheConfigAuditView` saw `CREATED` where it expected
  `UPDATED` because another test had re-created the policy meanwhile — and the
  class took ~75 s sequentially and ~75 s in parallel, so there was no wall-clock
  win to trade the risk for. Real parallelism needs per-test policy isolation.
- A PostgreSQL schema gate (`PostgresSchemaTest` + a `schema` CI job) so
  entity/migration drift fails the build instead of hiding. Every other test
  runs on H2 with `ddl-auto: create-drop`, which means Hibernate builds the
  schema *from the entities* and Flyway never runs — so a column an entity
  declares but no migration creates, a column too narrow for its values, or an
  enum stored as a number would stay green in CI and break in production, which
  is PostgreSQL, the one database never exercised. The test boots the real
  application against a real PostgreSQL with the **production** datasource
  configuration, lets Flyway build the schema, and has
  Hibernate `validate` compare it against the entities, then round-trips a row
  to prove the mapping — including `OffsetDateTime` against `TIMESTAMPTZ`, the
  classic H2-passes/Postgres-fails difference.
  Two guards keep it honest: it asserts the JDBC product really is PostgreSQL
  and that `ddl-auto` really is `validate`, so it cannot silently pass on H2.
  Testcontainers starts an isolated PostgreSQL 16 locally and in CI; the schema
  suite is excluded from the H2 unit job and runs in its dedicated CI job. A
  local run passed all four schema checks. Testcontainers 1.21.4 is pinned
  because the Spring Boot 3.3.3 managed 1.19 client requests Docker API 1.32,
  which recent Docker daemons reject.
- Micrometer timers around every phase of the per-frame decision path
  (`decode`, `facedetect`, `onnxscore`, `heuristicscore`, `padheuristic`,
  `paddonx`, plus a wall-clock `total`), surfaced as a `pipelineTimings` block
  on the existing `GET /api/v1/metrics` with count / mean / max / p99 and each
  phase's share of the measured phases. Only `micrometer-core` was added
  (version managed by the Spring Boot parent, resolving to the same 1.13.3
  already on the classpath as `micrometer-observation`) — not
  `spring-boot-starter-actuator`, because the service already has its own
  metrics surface. Static holder by design: `ImageUtils` and the scoring
  services are constructed directly in unit tests as well as by Spring, so
  constructor injection would have meant threading a registry through every
  test for no benefit. Phases that never ran are **omitted** rather than
  reported as zero, so "fell back to the heuristic" is distinguishable from
  "never scored".
  **What it revealed** — measured over 12 frames of the real-face fixture,
  ONNX is 70% of pipeline time and the frame is detected *three* times:
  mean per frame is `decode` 21.7 ms, `facedetect` 74.5 ms, `onnxscore`
  126.5 ms, `paddonx` 116.9 ms, `padheuristic` 4.8 ms, request `total`
  442.0 ms. `PassiveScoringService.score()` and `assessPad()` each call
  `model.analyzeFrame(...)`, and that method runs its **own** Haar cascade
  internally (`OnnxMiniFasNetBackend.detectFaces`), so one frame pays for the
  HTTP face gate plus two more cascades and two BGR→RGB conversions on the same
  pixels. `paddonx` costing nearly as much as `onnxscore` is the duplicate work
  showing up in the numbers. The phases also do not sum to `total`: the ~98 ms
  gap is JSON, the session `findById`/`save` and the audit write
- `-Pslim` Maven profile producing a ~92 MB linux-x86_64 jar (down from
  ~209 MB) that still runs the full liveness/PAD pipeline. `org.openpnp:opencv`
  ships natives for eight platforms (109 MB) and `onnxruntime` for four plus a
  54 MB macOS `.dSYM`; a Linux runner and the Docker image load exactly one of
  each. The profile unpacks both jars into `BOOT-INF/classes` with the unused
  platform trees excluded, because spring-boot's `<excludes>` can only drop a
  whole dependency and cannot filter *inside* one. Note the trap it avoids:
  excluding the opencv dependency outright does **not** slim the app, it breaks
  it — the jar holds the Java bindings as well as the natives, so Spring cannot
  introspect beans whose signatures mention `Mat` and startup dies with
  `NoClassDefFoundError: org/opencv/core/Mat`. Removing natives while keeping
  the bindings is the only slimming that is actually safe. The profile also has
  to pin `<mainClass>`, because with a `<classifier>` set spring-boot 3.3.3
  auto-detects `Start-Class: ...loader.launch.JarLauncher`, making the loader
  re-launch itself until `StackOverflowError`. Opt-in only; the default build
  is byte-for-byte unchanged and still carries all seven natives
- Rate limiting (`RateLimitFilter`, no new dependency): session creation is
  capped per client IP (30/min) and frame + challenge-validate submissions
  share a per-session budget (60/10s) — over-limit POSTs get 429 + Retry-After
  before any body parsing or DB work; configurable via
  `mosip.security.rate-limit.*`
- Terminal PAD rejection proven end to end: new committed fixture
  `fixtures/print-attack.jpg` (15×15-blur degradation of `real-face.jpg` —
  one face still detectable, texture heuristic classifies it PRINTED_PHOTO and
  the MiniFASNet model independently flags it as an attack) plus
  `PrintAttackFixtureTest` (fast fixture contract) and a
  `LivenessPipelineIntegrationTest` case that pushes two attack frames over
  real HTTP and asserts the `retry_passive` → confirmed `reject` flow, the
  `presentation_attack:PRINTED_PHOTO` failure reason, the `PAD_REJECTED` audit
  entry, and terminality (a third frame gets 409)
- Browser console can now edit the config policy: a per-workflow policy editor
  (threshold, challenge timeout, min challenges, retries, failure policy,
  enabled flags, challenge types) that loads via the open `GET` and saves via
  admin-key `PUT` — the key lives in the tab's sessionStorage, is masked, and is
  never written to the decision log
- GitHub Actions CI (`.github/workflows/ci.yml`) — runs on every push as **two
  parallel jobs**: unit tests (253, surefire
  `!LivenessPipelineIntegrationTest,!PostgresSchemaTest` filter) and the
  full-app integration suite (10), each with Temurin JDK 17,
  Maven dependency cache, `fail-fast: false` and a least-privilege token; a
  gated third job then packages the Spring Boot executable jar (only when both
  suites pass) and uploads it as the `pad-liveness-backend-jar` artifact
  (7-day retention). The upload step is additionally gated on the push ref
  matching the repository's default branch, so feature-branch pushes still
  prove the jar packages but no longer spend the shared artifact quota on
  commits nobody downloads. The package job now also **boots the jar it just
  built** and asserts `GET /health` returns `engine: available` before anything
  is published — on every branch, not just the default one. The test jobs run
  against `target/classes` with the full `~/.m2` classpath, so nothing could
  catch a packaging-only regression (fat jar missing its OpenCV natives, wrong
  `Start-Class`, truncated repackage): those all shipped green and only broke
  for whoever ran the artifact. `/health` always answers `status: ok`, so the
  200 proves nothing on its own — the assertion is on `engine`, which reflects
  whether the natives actually loaded. Pushing a **version tag (`v*`) now also
  publishes that same jar to a GitHub Release**: the `release` job
  re-packages **with the tag's version stamped in** — `v1.0.1` builds
  as `1.0.1`, so the jar's manifest and the asset name match the
  release instead of reading `1.0.0-SNAPSHOT` — then smoke tests it
  and attaches it with `gh release create` (falling back to
  `gh release upload --clobber` when the release already exists, so a re-pushed
  tag converges instead of failing). It carries `contents: write` scoped to
  that one job — the rest of the workflow stays read-only — and authenticates
  with the automatic per-run `GITHUB_TOKEN`, so no secret must be configured.
  The per-push artifact upload still fires on tag pushes (its guard
  includes `v*`), and the release job builds its own stamped copy —
  CI jobs have no shared filesystem, so the two never exchange files.
  `--notes-start-tag` is deliberately omitted because it errors when there is
  no previous release, which would make the very first tag unpublishable.
  The smoke test moved
  from inline YAML into `scripts/smoke-jar.sh` so both jobs share it and it
  stays runnable locally
- `LivenessPipelineIntegrationTest` — boots the full app with H2 (dev profile)
  on a random port and drives the real pipeline over HTTP with no mocked beans:
  passive pass → PASSED, below-threshold escalation → challenge issued →
  validation stays `continue` inside the 15s window, active-disabled reject →
  FAILED, undecodable frame → 422, plus admin-key config updates and security
  headers on real responses
- The browser's policy-save path is now covered end to end:
  `browserStyleConfigPut_isAcceptedWithTheAdminKey` sends a real preflight
  (`Origin` + `Access-Control-Request-*`) and the console's own same-origin PUT
  with Chrome headers and `X-Admin-API-Key`, then asserts the preflight allows
  the admin header, the PUT is applied *and* audited, the same request without
  the key is 403, and a foreign origin is 403 with no policy change. It needed
  the JDK HTTP client rather than `TestRestTemplate`: `HttpURLConnection`
  silently strips `Origin` and `Access-Control-Request-*` as restricted
  headers, so a preflight written that way is indistinguishable from a plain
  same-origin OPTIONS and the test would pass without exercising CORS at all
- Config changes are now audited and reviewable: every successful
  `PUT /api/v1/config/{workflowType}` writes a `CONFIG_CHANGED` entry **in the
  same transaction as the policy update**, recording only the fields that
  actually moved with their old → new values, `CREATED` vs `UPDATED`, and a
  truncated SHA-256 fingerprint of the admin key (never the key itself).
  Rejected requests (no/invalid key, invalid value) record nothing, so a saved
  policy can never be silent and a rollback can never leave a phantom entry.
  Previously a policy edit was entirely untraceable — `audit_logs.session_id`
  was `NOT NULL`, so an operator-level event could not be recorded at all.
  New `GET /api/v1/config/audit?limit=50` (newest first, open read like the
  other config GETs) plus a **Policy change history** panel in the browser
  console, refreshed automatically after each save. Migration V4 makes
  `audit_logs.session_id` nullable (`NULL` = operator event, never a pipeline
  event) and indexes `created_at`; session trails query by session id, so they
  can never pick one up
- Challenge-window timeout proven end to end: a real-time
  `LivenessPipelineIntegrationTest` case configures a 1s challenge window with
  `maxRetryCount=2` (so the engine's floor is what must keep the challenge
  open), waits out both windows, and drives `continue` → `retry_challenge`
  (fresh challenge, attempt 2, different action) → terminal `reject` with
  `max_retries_exceeded`, asserting the paired CHALLENGE_ISSUED/
  CHALLENGE_FAILED audit entries, the FAILED session and close summary, and a
  409 when the failed challenge is re-validated

### Changed
- The console's **Policy change history** now loads with the page instead of
  only after clicking refresh, so the most recent edit — its key fingerprint,
  risk level and field diff — is on screen when the console opens. Previously
  the fingerprint and the risk badge were rendered but hidden behind a click,
  which meant the panel an operator was meant to check before saving was the
  one they had not yet loaded. The load is the same open `GET` as the policy
  read (no admin key needed) and a failure only writes the status line, so a
  history outage cannot block editing.
- **Release assets carry the tag version.** `v1.0.1` now builds as
  `1.0.1` — the pom switched to Maven's CI-friendly `${revision}`
  property (default `1.0.0-SNAPSHOT`, so every other build keeps its
  familiar artifact name), overridden only in the release job. The job
  asserts the stamp landed, because a silent miss would still build
  green and publish a SNAPSHOT-named asset. The attach step's
  `ls | grep` also became a nullglob loop, clearing the last
  actionlint warning
- **CI now builds, boots and publishes both jars.** The package job is a two-leg
  matrix: the full cross-platform jar (~200 MB) and the slim linux-x86_64 one
  (~94 MB). Both legs boot and must report `engine: available` — that is what
  catches a dependency bump which quietly breaks the slim profile's native
  filter, which otherwise ships a jar that boots fine and then rejects every
  frame. The run summary reports both sizes, and `scripts/check-jar-size.sh`
  fails the build when a jar outgrows its budget (245 MiB full, 110 MiB slim):
  a jar that quietly re-adds a platform breaks no test, it just doubles
  artifact storage.
- **Artifact names follow the primary download.** `pad-liveness-backend-jar` is
  now the **slim** jar — it is what CI runners, the Docker image and most
  deployments run, and it is the small one. The cross-platform jar moves to
  `pad-liveness-backend-full-jar`, one click away for macOS/Windows consumers.
  Each leg writes its own name because upload-artifact v4 treats a name as
  immutable across jobs: a shared name fails the second leg with 409.
- **Uploads also fire on `v*` tags** (the default branch used to be the only
  publisher), so a release tag gets a versioned artifact independent of which
  branch is the default. `pull_request` is now a trigger as well: branches run
  the identical gate before merging, and the upload guard keeps PRs — forks
  included — out of artifact storage.
- **The Docker image is built from the slim profile**, and the jar is copied
  with `--chown` rather than a `chown -R` layer that stored the same file twice:
  **620 MB → 294 MB** of layers, measured. The COPY is pinned to the `-slim`
  classifier because a slim build leaves the thin jar beside the repackaged one,
  which made the old `*.jar` glob ambiguous. `scripts/smoke-docker.sh` builds the
  image, boots the container and asserts `/health`, the unprivileged user, and
  reports the layer total — the image path had no coverage at all before, so a
  broken `COPY` or base image shipped green.
- `scripts/smoke-jar.sh` takes `SMOKE_JAR=full|slim` (default `full`), selects
  with globs instead of `ls | grep`, and says which jar it is about to boot.
  Its container sibling asserts `status: ok` rather than `engine: available`:
  Alpine is musl and the OpenCV native is glibc-linked, so a correct image serves
  the API with frame processing unavailable by design.
- Rate-limited requests are now counted per rule and reported by
  `GET /api/v1/metrics`: `rateLimitedRequests` (total refused) with the split
  `rateLimitedSessionCreate` / `rateLimitedFrames`, plus
  `rateLimitAllowedRequests` and `rateLimitRejectionRate`, so an operator can
  see the limiter biting (and which rule bites) from the API instead of container
  logs. The tallies live in a new `RateLimitCounters` component rather than on
  the filter, because a mocked `Filter` bean inside a `@WebMvcTest` slice is
  auto-registered as a filter and swallows every request before it reaches a
  handler — and because the metrics controller should not depend on servlet
  plumbing. These counters are per-process: they start at zero on restart,
  unlike the session metrics in the same payload, which are queried from the
  database; `rateLimitRejectionRate` is `0.0`, never `NaN`, with no traffic
- Rate limiting now works behind Docker port-mapping and reverse proxies:
  `mosip.security.rate-limit.trusted-proxies` (`TRUSTED_PROXIES`, comma-separated
  IPs/CIDRs, **default empty = trust nobody**) lists the peers whose
  `X-Forwarded-For` / `X-Real-IP` may be believed when keying the per-IP session
  budget. Forwarded headers are honoured *only* from a trusted peer, and the
  chain is walked right-to-left so the first untrusted hop is the client — a
  client cannot mint extra budgets (or poison someone else's) by sending the
  headers itself, which is why the default is empty and why listing a range
  containing untrusted addresses is called out as unsafe. Before this, every
  client behind NAT shared one budget, the ceiling documented on the filter.
  Hops may carry a port (`10.0.0.1:51234`) or bracketed IPv6 (`[::1]:443`); junk
  and hostname entries are ignored rather than resolved (no DNS in the request
  path). Frame budgets stay keyed by session id
- The rate limiter now **publishes its budget** instead of only failing: every
  limited route returns `X-RateLimit-Bucket` (`session-create` / `frames`),
  `-Limit`, `-Remaining` and `-Reset` (seconds into the window) on *allowed*
  requests as well as 429s, so a client can pace itself rather than discover the
  limiter through a failure. They are added to CORS
  `Access-Control-Expose-Headers` for a console on another dev port, and
  `Retry-After` stays exclusive to the 429
- The console gained a **Request budget** panel: a live meter per limiter budget
  showing how many submissions remain and when the window resets, counting down
  locally every second (no polling), going amber at ≤25% and red on a 429 with
  its retry time. Verified end to end in a browser against a running service,
  including burning the whole session-create budget to watch it trip
- The console's policy editor no longer loses unsaved edits silently. Loading a
  workflow replaced every field, so switching workflow (or pressing Load)
  threw away a threshold the operator had just tuned with no warning. The form
  is now compared against the last loaded/saved state: an amber "Unsaved changes
  to the \<workflow\> policy" marker appears while edits are pending (and clears
  again if a field is reverted by hand), and switching workflow, Load and
  closing/reloading the tab all confirm first — cancelling snaps the workflow
  select back so the UI matches what is actually loaded. The guard fails closed
  if `confirm()` is unavailable (embedded webviews that block dialogs): the edits
  are kept rather than dropped
- The console no longer leaves the admin key sitting in the page: it used to be
  written straight into the input's value on every load, so the browser's
  password masking was the only thing between the secret and a screenshot, a
  screen share or devtools. The key now lives only in this tab's sessionStorage;
  the field is a typing buffer that is cleared on blur and after every save, a
  hint shows a partial mask (`••••••••••••-9f3`, the same idea as the audit
  trail's key fingerprint) so the operator can still tell which key is loaded,
  and **Forget key** removes it from the tab. Saving is unaffected — the key is
  sent from the stored value, verified against a live server
- The challenge-window floor is now `mosip.liveness.min-challenge-window-ms`
  (default **15 000** — unchanged for production, and hard-clamped to 1 000 ms,
  the smallest `challengeTimeoutMs` the config API accepts). It is the guard
  against a shortened or legacy policy row failing someone who needed a moment,
  so raising it only ever makes the flow more patient. Lowering it is a test
  seam: the e2e timeout case now runs 3 s windows instead of 2 × 15 s, cutting  that test 34.4 s → 10.0 s and the integration class 54 s → 30 s
  (93 s → 70 s in a cold, isolated CI-job run) with identical
  assertions. The 15 s default and
  the 1 s clamp stay pinned by `DecisionEngineServiceTest` (3 new
  cases, no sleeps). Everything else the class spends is real work —
  ~15 s of OpenCV face detection and ONNX scoring across 25 frames
  plus a ~37–43 s Spring context load (per-phase breakdown in the
  Performance section below);
  Flyway cannot replace that DDL on H2 because the migrations are
  Postgres-specific (`TIMESTAMPTZ`), so dev/integration boots keep
  `ddl-auto: create-drop`
- OpenCV (and the MiniFASNet model) now load **lazily on first use** — no boot
  step waits for either. `PadLivenessApplication.main()` still starts an
  `opencv-warmup` daemon thread before `SpringApplication.run()` so the ~65 MB
  extraction overlaps context refresh, and `PassiveScoringService` resolves its
  scorer (natives + model) on its first caller instead of in its constructor;
  `ensureOpenCvLoaded()` remains the synchronous barrier — it loads at most once
  and blocks only if the background attempt has not settled by the time a frame
  arrives, which is the rare case (a boot that reaches a request takes longer
  than the load). `isOpenCvAvailable()` (a plain non-blocking read) is therefore
  eventually consistent at boot: `/health` reports the engine as unavailable
  until the warm settles — accurate, and what the smoke test waits for.
  `openCvAttempted` is volatile since the fast path is now read across threads.
  **Measured earlier for the overlap-only variant, and the headline claim did
  not hold.** Seven interleaved A/B boots per variant on the same box: baseline
  mean 46.47 s / median 46.02 s, warm-up mean 47.25 s / median 48.14 s —
  indistinguishable. The timeline shows the load *does* leave the critical path
  (baseline occupies +35.5 s → +41.5 s, warm +0 s → +4.8 s), but the warm
  thread costs ~4.5 s in CPU contention with the main thread on 4 cores, netting
  ~1.5 s in the best pair. The original "~6 s" figure came from a cold page
  cache; warm, the load is only ~3.8 s and there is nothing to hide. It may
  still pay off on cold-cache CI runners, which could not be verified here
  (dropping caches needs root). Kept because it is behaviour-neutral and correct,
  not because it is proven faster.
  Two findings worth keeping: logback **silently drops** events logged from the
  warm thread, because it starts before Spring Boot initialises its logging
  system — the JVM's own fat-jar class-loading can outlast the entire native
  load, so the attempt may settle before `run()` even begins. The outcome is
  therefore reported from the main thread: from `init()` once Spring is
  configuring beans (the common case — the line lands ~16 s before
  `Started PadLivenessApplication`), or, if the attempt is still in flight, by a
  short-lived daemon that joins the warm-up thread and reports the moment it
  settles; `ensureOpenCvLoaded()`'s caller is the last resort on the first
  frame. The warm thread itself never logs. The outcome line carries the
  measured millisecond cost.

### Security
- `PUT /api/v1/config/{workflowType}` now requires an `X-Admin-API-Key` header
  matching `MOSIP_ADMIN_API_KEY` — fail-closed: config updates are refused when
  no key is configured, so unauthenticated callers can no longer lower the
  liveness threshold and defeat PAD
- Added `SecurityHeadersFilter`: `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, `Cross-Origin-Opener-Policy`, `Permissions-Policy` and a
  strict `Content-Security-Policy` (`script-src 'self'`, `style-src 'self'`,
  `frame-ancestors 'none'`) on every response (Swagger UI exempt from CSP only)
- POST/PUT bodies over `MAX_REQUEST_BODY_BYTES` (default 24 MB) are rejected
  with 413 before reaching a controller; frame DTOs gained `@Size` caps
- Frame decoding now enforces payload and dimension caps (decompression-bomb
  guard) and releases leaked `Mat` buffers on the error paths
- Fixed CORS: `allowedOriginPatterns` (the previous `allowedOrigins` wildcard
  ports never matched any real origin), methods limited to GET/POST/PUT,
  credentials off
- Python service: replaced wildcard `allow_origins=["*"]` + credentials with an
  origin allow-list (`settings.CORS_ORIGINS`), added the same security headers
  and a body-size cap
- Containers: image runs as non-root, JVM capped (`MaxRAMPercentage=50`,
  `UseSerialGC`, `ExitOnOutOfMemoryError`), PostgreSQL published on loopback
  only; `server.error.include-stacktrace/message: never`

### Fixed
- Invalid path parameters (e.g. a non-UUID `sessionId`) returned 500 — now 400
  `VALIDATION_ERROR` via `MethodArgumentTypeMismatchException` handling
- `DeviceAdapterTest` race: `mockDeviceCorruptFrames` and
  `mockDeviceLifecycleAndFrameDelivery` asserted exact event counts after their
  latches opened while capture keeps producing events until `stopCapture()` —
  they now assert the guaranteed lower bound (flaked under load)

### Performance
- Console script/style moved to `static/console.js` / `static/console.css`
  (cacheable, enables the strict CSP; no inline scripts or styles remain)
- Frames larger than 1280 px are downscaled before analysis so 1080p/4K cameras
  cost the same CPU as 640x480 (low-end hosts)
- Console: single reused capture canvas instead of one per frame, bounded
  decision-log size, health polling paused while the tab is hidden
- Server: Hikari pool 10 → 5, Tomcat threads 200 → 50, response gzip enabled
- Startup cost breakdown, measured on the packaged jar (dev profile,
  cold native cache, `BufferingApplicationStartup` across 412 steps,
  three runs; wall clock 37–43 s, of which **~4 s is JVM launch and
  fat-jar class loading** before Spring's clock even starts — the
  "process running for" figure minus Spring's own seconds). The
  remaining ~30 s of `spring.context.refresh` is, by self time:
  Hibernate SessionFactory construction **~7.8 s** (probed with
  `ddl-auto=none`: unchanged at 7.76 s, so it is the metamodel
  build, **not** DDL — the old "Hibernate DDL 7.8 s" attribution
  was wrong), `@Configuration` class parsing **~6 s** (135 classes
  read through the jar-in-jar loader, ~45 ms each), Spring Data
  repository proxies **~4 s** (the first repository pays the shared
  JPA metamodel, ~2.4 s), the dev-only H2 console bean ~1.5 s,
  Tomcat creation ~1 s, and the rest spread over ~400 smaller steps
  (springdoc, AOP, handler mappings, post-processors). The slim jar
  (94 MB) shows the same distribution — artifact size is not a
  factor — and OpenCV natives no longer appear on the main-thread
  timeline at all: the warm-up thread overlaps completely. The real
  boot-time bottleneck is Hibernate's metamodel and Spring's own
  config parsing; candidates for future work are
  `hibernate.temp.use_jdbc_metadata_defaults=false` (skips JDBC
  metadata introspection), excluding unused auto-configurations
  (135 parsed classes), and an AppCDS archive for the pre-Spring
  class loading

## [Unreleased] - 2026-10-01

### Fixed
- Resolved intermittent test failures in DeviceAdapterTest
- All test suites now pass consistently
- Improved test reliability for device adapter components
