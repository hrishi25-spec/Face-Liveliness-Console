# Android Registration Client — Liveness Gate Integration

Implements the *Face Liveness + PAD orchestration* spec on top of this repo's
shared engine (`src/main/java/io/mosip/liveness/**`). One orchestrator drives
liveness for all three workflows (resident capture, operator auth, supervisor
auth) so behaviour is identical online/offline and across roles.

## What is engine-tested vs Android-only

| Tree | Runs where | Notes |
|---|---|---|
| `src/main/java/io/mosip/liveness/android/**` | JVM (this repo) | Full state machine, policy floor, evidence signing — covered by `AndroidLivenessOrchestratorTest` (13 tests) and the shared engine suite (349 green). |
| `pigeon/liveness.dart` | Regenerated code | Host/Flutter API definitions (spec §7). |
| `lib/liveness/**` | Flutter | `LivenessViewModel` + `LivenessView`: renders state and prompts (i18n keys), retry; capture button enabled only on PASSED (§14). |
| `android/app/src/main/java/io/mosip/registration/liveness/**` | Android embedding | `CameraXFaceSource`, `AndroidKeystoreEvidenceSigner`, and generated-Pigeon bridge. Compiled by the Android Gradle harness; the downstream MOSIP host still wires the production collaborators and registration/auth flows. |

## Reproducible build

The checked-in Flutter app is a build harness for these integration sources,
not a replacement for the MOSIP Registration Client. It exercises the Dart
entry point, generated Pigeon bindings, Android glue, and the shared Java
engine sources. The harness starts in a fail-closed unavailable-device state
until a host registers the production API and camera pipeline.

The toolchain is pinned to Flutter **3.10.4** (bundled Dart **3.0.3**), Pigeon
**10.0.1**, JDK **17**, Gradle **8.4**, Android Gradle Plugin **8.3.2**, Android
SDK/build tools **34**, and NDK **25.1.8937393**. Flutter includes the Dart SDK;
do not install a separate Dart version for this project. Install the pinned
Android SDK packages and accept their licenses before building locally.

From this directory:

```bash
flutter --version
flutter pub get --enforce-lockfile
./tool/generate_pigeon.sh
dart format --output=none --set-exit-if-changed lib pigeon
flutter analyze
flutter build apk --debug
```

The generated Dart and Java bindings are checked in. CI regenerates them and
fails if either output differs, then builds `build/app/outputs/flutter-apk/app-debug.apk`.
The Gradle wrapper verifies its distribution checksum and Gradle dependency
versions are locked.

`tool/generate_pigeon.sh` runs `dart format` on the Dart binding after Pigeon
writes it: Pigeon 10.0.1's raw output is not `dart format`-clean, so without
that step the regenerate-and-diff gate and the format gate could never both
pass. The Java output is left exactly as Pigeon emits it.

The Gradle module does not compile all of `src/main/java` — it pulls in an
explicit pattern list (orchestrator, engine core, policy, audit, evidence)
that is free of Spring, OpenCV, and ONNX dependencies. The list is set with
`setIncludes`, not `include`: the Java plugin seeds the source set with
`**/*.java`, and `include` only adds to that list, which would sweep in every
file under `src/main/java`. A new engine type used by these sources must be
added to the list in `android/app/build.gradle`, or the Android build fails on
the missing symbol.

## Engine-side components (already in `src/main/java`)

| Component | Responsibility (spec §2) |
|---|---|
| `AndroidLivenessOrchestrator` | State machine (§4), attempt/retry counting, session nonces, gate validity, audit |
| `AndroidLivenessPolicyProvider` | Per-role resolution over the shared `WorkflowPolicyDefaults` table + hard security floor (§3) |
| `FaceFrameSource` + `MockFaceFrameSource` | Vendor-neutral frame SPI; mock for tests/dev (§11) |
| `LivenessEvidence(Signer)` | Signed evidence record, SHA-256withRSA (analyse.md §5.4, §8 `pass()`) |
| `ModelStore` / `InMemoryModelStore` / `SignedManifestModelStore` | Model activation bound to a vendor-signed manifest (spec §13: signature → sha256 → atomic swap → rollback); tamper-tested by `SignedManifestModelStoreTest` |
| `LockoutStore` | `lockoutUntil` persistence for `LOCKOUT_TEMPORARY` (§8, §12) |

Design invariants enforced (§1): fail closed (R1); frames never cross the
Pigeon channel (R2); orchestrator selects challenges (R3); no network in any
decision (R4); UI gets generic i18n keys only (R5).

## Flutter-side integration (§14)

1. Generate the Pigeon code:
   `./tool/generate_pigeon.sh`
2. Host a `LivenessViewModel(hostApi: PigeonLivenessHost())` (provider/riverpod),
   call `bindFlutterApi()`, and render `LivenessView`
   inside the resident face-capture screen and the operator/supervisor
   biometric login dialogs (role param).
3. Add the §9 message keys to `intl_*.arb` files (defaults in
   `LivenessView.defaultMessages`).
4. Enable the capture button only when `viewModel.captureEnabled` is true and
   `ensureGateValidForCapture()` returns true (gate validity window, §8).

## Android embedding steps

1. Add the engine jar (`pad-liveness-backend` or the extracted
   `io.mosip.liveness` classes) to `clientmanager`'s dependencies
   (see docs/client-integration-guide.md §4.1).
2. Copy `android/app/src/main/java/io/mosip/registration/liveness/**` into the
   client's Android module; declare CameraX deps and ProGuard keep rules for
   `io.mosip.liveness.**` (§14 build).
3. In the Flutter `Activity`, construct `LivenessPigeonBridge` with the
   `FlutterEngine` and Activity as `LifecycleOwner`, call `setUp` with the
   pipeline/backend/policy/signing collaborators, and apply `FLAG_SECURE` on liveness screens
   (`LivenessPigeonBridge.applyFlagSecure`).
4. Wire `AndroidModelStore` (this directory): it reads the vendor-signed
   manifest + model bundle from disk and delegates verification to the
   engine's `SignedManifestModelStore` (§13: signature → sha256 → atomic
   swap → rollback — fail-closed, tamper-tested in the engine suite). Back
   `LockoutStore` with Room so `lockoutUntil` survives process death (§12).

### Producing a signed model manifest (deployments)

`AndroidModelStore.install(manifestFile, modelFile)` only accepts the five-key
properties format signed by the vendor key, so whoever packages a model
release generates that file with the engine jar's tool (spec §13):

```bash
# one-time: bootstrap a vendor keypair (or pass your own PKCS#8/PKCS#1 PEM key)
java -cp target/pad-liveness-backend-*.jar \
  io.mosip.liveness.tools.ModelManifestTool keygen \
  --private vendor-private.pem --public vendor-public.pem

# per release: hash the artifact, sign, write the manifest beside it
java -cp target/pad-liveness-backend-*.jar \
  io.mosip.liveness.tools.ModelManifestTool sign \
  --model-id minifasnet --version 2026.10.1 --min-app-version 1.4.0 \
  --key vendor-private.pem --out model.manifest.properties minifasnet.tflite
```

The output is exactly what `AndroidModelStore` reads — `modelId`, `version`,
`sha256`, `minAppVersion`, `keyId`, `signature` (SHA256withRSA over
`modelId|version|sha256|minAppVersion`) — written deterministically, and the
tool re-reads and re-verifies its own output before returning. Ship the model
file and the manifest together; the client refuses anything that does not
verify against a trusted public key.

**Signing-key rotation.** `sign` stamps `keyId` — the SHA-256 hex of the
signing key's X.509 encoding, re-derivable outside the tool with
`openssl pkey -pubin -in vendor-public.pem -outform DER | sha256sum`. The
client selects the verifying key by that id: trust several keys at once with
`new AndroidModelStore(appVersion, previousKey, currentKey)` (engine side:
`new SignedManifestModelStore(appVersion, previousKey, currentKey)`) so old
and new manifests both install during the rotation window. A manifest whose
id names a key no longer in the ring is refused — and so is a pre-keyid
manifest once its key has been removed, so rotate by keeping the old key
until its last manifest is re-signed, then drop it.

## Version constraints (verified against MOSIP docs + the RC repo, 2026-10-06)

Sources: MOSIP Docs 1.2.0 *Technology Stack* page (via the repo's research
note) and the live `mosip/android-registration-client` repository (`main`,
v1.1.1).

| Pin | Value | Source / notes |
|---|---|---|
| Flutter | **3.10.4** | Docs 1.2.0 Technology Stack; RC repo README |
| Dart | **3.0.3** (Flutter 3.10.4 bundle) | same Technology Stack page |
| Android compile SDK | Docs say **31**; RC repo `main` uses **`compileSdkVersion 34`** | **docs-vs-repo drift** — the repo has moved on (AGP 8.x comment in `pubspec` history); pin to the repo at integration, keep `targetSdk` = `flutter.targetSdkVersion` as the repo does |
| min SDK | **28** (RC repo) | the liveness module needs no raise |
| MOSIP platform | **1.2.0.1 (Java 11 backend)** compatible; 1.2.1.0 (Java 21) listed not compatible | this is a *backend platform* figure, not the app's language level; an upstream `j11-to-j21` migration page exists |
| RC app language level | **Java 21** (`sourceCompatibility`/`targetCompatibility VERSION_21`, core-library desugaring on) | RC repo `android/app/build.gradle` (`main`) |
| Engine (this repo) | **Java 17** (`pom.xml` `<java.version>17</java.version>`; records + switch expressions) | class file 61 |

### The Java-11-vs-17 engine-jar question — resolved

- **Android embedding:** the RC app compiles at Java 21 with desugaring, so
  the engine jar built at `--release 17` (class file 61) links and dexes
  cleanly — `javac 21` reads it, D8/R8 translates it. Verified against the
  repo's `compileOptions`, not assumed.
- **A fork still pinned to Java 11 compileOptions cannot link the jar**
  (records require ≥ 16): raise that build to ≥ 17; there is no source-level
  fallback that keeps records off the table.
- **Backend platform:** the Java 11 figure belongs to MOSIP platform
  1.2.0.1. This repo's Spring Boot **service** (3.3.3) requires JDK ≥ 17, so
  it cannot run *on* a Java-11 platform runtime — deploy it on 17+ or follow
  the platform's own j11→j21 path. The Android embed takes only the
  `io.mosip.liveness` classes, never Spring.

### CameraX choice

- **Pin `androidx.camera:*:1.3.x` (≥ 1.3.0).** `CameraXFaceSource` uses
  `ResolutionSelector` / `ResolutionStrategy` / `AspectRatioStrategy`, which
  exist only from CameraX **1.3.0** (the legacy `setTargetResolution` was
  deprecated there — verified in the androidx camera release notes).
- Compatible with the RC repo's `compileSdkVersion 34`.
- **Fallback for an SDK-31 toolchain** (if integrating against the docs-31
  stack instead): use CameraX **1.2.x** and rewrite `open()` to the legacy
  `setTargetResolution(Size)` path — the `resolutionselector` classes do not
  exist before 1.3.
- The RC repo declares no CameraX dependency today (device capture goes
  through the SBI app), so this is a **new dependency** to add alongside the
  ProGuard keep rules in the embedding steps above.

### Pigeon choice

- **Pin `pigeon: ^10.0.1`** — exactly what the RC repo's own
  `pubspec.yaml` declares (verified on `main`), so generated code matches the
  repo's `pigeon.sh` workflow instead of introducing a second generator
  version.
- Regenerate with `dart run pigeon --input pigeon/liveness.dart` (the input
  file uses Pigeon-10-compatible `@HostApi`/`@FlutterApi` annotations).
- **Flagged repo drift:** RC `pubspec.yaml` still carries
  `environment: sdk: ">=2.19.6 <3.0.0"` while the docs state Dart 3.0.3 —
  reconcile that constraint in the RC repo when integrating.

## Auto-logout interplay with liveness sessions

Docs 1.2.0 lists **"Auto logout after inactivity"** and calls out the
interaction explicitly: *avoid logout mid-challenge or pause the timer*. The
race: a liveness gate is an active, seconds-to-minutes operation during which
the user may not tap the screen.

**How long a gate can hold the screen (bounded by code, not hope):**

- One attempt is capped by the engine at `maxSessionDurationMs`
  (**default 30 s**) and fails closed with `SESSION_TIMEOUT`.
- Attempts are capped by `CEILING_MAX_RETRIES` (**5**) — so a worst-case gate
  is ≈ **5 × 30 s = 150 s**, and a typical passive pass is **under 10 s**.
- A flow that stacks gates (resident capture → operator auth → supervisor
  auth) multiplies that; size the timer against the stack, not one gate.

**Decision (host-side pause; the module cannot pause the timer itself):**
the inactivity watchdog lives in the RC app, and the liveness module only
emits events. The integration contract is:

1. On `startSession` success → suppress/refresh the inactivity countdown.
2. Refresh again on every `onState` event (prompt/progress = live user).
3. On `onFinal` (`PASSED` | `TERMINAL_FAILURE` | `ABORTED`) → resume the
   normal countdown.
4. As a floor, the RC inactivity timeout should exceed the worst-case gate
   (150 s) even without pause support.

**Fail-safe if logout fires anyway** (old RC build, timer raced): the
`LivenessViewModel` is disposed with the screen → `cancelSession()` →
session ends `ABORTED` → the gate fails closed; the next screen demands
re-login, and stale evidence never satisfies `ensureGateValidForCapture()`
(expiry/abort are both fail-closed — covered by the engine test suite). So
the race costs a retry, never a bypass.

Until the RC host wires steps 1–3, this remains *integration-pending*
(tracker G4, in progress).

## Acceptance coverage (§15)

Verified by tests in this repo: passive pass without user action; low score →
engine-selected challenge (user never chooses); PAD blocks with the generic
message; device errors never consume the retry budget; missing model fails
closed; retry limit + lockout persisted; gate validity/expiry enforced before
capture; unsigned evidence withheld (fail closed); policy floor cannot be
weakened; config cannot disable liveness without the build flag.

On-device-only items (need hardware/vendor SBI): APCER/BPCER report on a real
attack set; two physical frame-source adapters; performance targets on the
low-end device.
