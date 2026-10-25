# MOSIP Face Liveness & PAD Engine — Configuration Guide

## Base Configuration

Built via `LivenessConfig.builder()`:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `livenessEnabled` | boolean | `true` | Enable/disable liveness verification. When disabled, passive scoring and active challenges are skipped, but the face-detection gate and **PAD stay fail-closed** — a confirmed presentation attack still rejects. Every bypassing session is audited as `LIVENESS_DISABLED`. |
| `activeLivenessEnabled` | boolean | `true` | Enable/disable active (Stage 2) liveness. When disabled and passive fails, session hard-fails instead of escalating. |
| `passiveThreshold` | double | `0.80` | Liveness confidence threshold in [0, 1]. Median score ≥ threshold → proceed; < threshold → escalate (or fail). |
| `minFaceQuality` | double | `0.50` | Minimum face quality score in [0, 1]. Frames below this are rejected as RETRYABLE_ERROR. |
| `passiveMinFrames` | int | `5` | Minimum consecutive frames before a passive liveness decision is made. |
| `passiveWindowFrames` | int | `7` | Sliding window size for temporal median voting. Must be ≥ `passiveMinFrames`. |
| `minChallengeCount` | int | `2` | Minimum number of distinct challenges that must pass before active liveness is satisfied. |
| `challengeTimeoutMs` | long | `15,000` | How long (ms) a challenge stays open for the user to perform the action. Lowered from 60,000 to 15,000 at the product's request; the floor below is applied at runtime. Exceeding the window consumes one retry. |
| `min-challenge-window-ms` (`mosip.liveness`) | long | `15,000` (hard min `1,000`) | Floor a stored `challengeTimeoutMs` is raised to, so a shortened or legacy policy row cannot turn a person who needed a moment into a certain failure. Raising it only makes the flow more patient; production lowers it only in tests (the e2e timeout path runs 3s windows instead of 2 × 15s). |
| `maxRetries` | int | `2` | Number of retries (on top of the initial attempt) before hard failure. Total attempts = 1 + maxRetries. |
| `supportedChallengeTypes` | Set\<ChallengeType\> | All 5 types | Pool of challenge types the engine may select from. |
| `onRepeatedFailure` | RepeatedFailureAction | `LOCK_OUT` | Behavior when retry budget is exhausted: LOCK_OUT, FALLBACK, or ESCALATE_TO_OPERATOR. |
| `workflowOverrides` | Map\<WorkflowType, LivenessPolicy\> | empty | Per-workflow policy overrides (see below). |

### Validation Rules

Validated by `EffectivePolicyValidator` at **session creation** (the frozen
snapshot must be valid before it is persisted) and on every
`PUT /api/v1/config/{workflowType}`. A violation is a 400.

- `passiveThreshold` ∈ **(0, 1]** — a threshold of exactly 0 would pass every frame
- `minFaceQuality` ∈ [0, 1]
- `passiveMinFrames` ≥ 1
- `passiveWindowFrames` ≥ `passiveMinFrames`
- `minChallengeCount` ≥ 1 (and **≤ the number of configured challenge types** when active liveness is on)
- `maxRetries` ≥ 0
- `challengeTimeoutMs` ≥ `mosip.liveness.min-challenge-window-ms`
  (**15,000** by default, never below the engine's 1,000 clamp) — a shorter
  window is refused by the API and again when a session freezes its snapshot
- `mosip.liveness.min-challenge-window-ms` ≥ `1,000` (values below are clamped up)

---

## Auditing policy edits

Every `PUT /api/v1/config/{workflowType}` writes one `CONFIG_CHANGED` entry to
`audit_logs` **in the same transaction as the update**, so a saved policy can
never be silent and a rolled-back update can never leave a phantom entry. A
rejected request (no session, a role that may not write, invalid value) writes nothing at all.

```
GET /api/v1/config/audit?limit=50     # newest first, 1–500
GET /api/v1/config/audit?limit=50&workflowType=OPERATOR   # filtered in SQL, not in the client
GET /api/v1/config/audit/verify          # walks the whole tamper-evident chain
```

`workflowType` is optional; omitting it returns every workflow. The filter is  pushed into the query (`audit_logs.workflow_type`, indexed by V8) because the
audit table grows a row per frame decision — filtering after the fact would page
through the whole pipeline history to answer a policy question. An unknown value
is a 400, not a silently unfiltered feed.

Every `CONFIG_CHANGED` row is chained (`prev_hash` → `entry_hash`), and
PostgreSQL rejects `UPDATE` and `DELETE` on `audit_logs`. `/audit/verify`
reports the first break and whether it was an edited row or a deleted one.

### Keying the chain (`MOSIP_AUDIT_HMAC_SECRET`)

The chain's hash is `H(prev_hash ‖ canonical(entry))` over a canonical form
that is stable across a database round-trip. Unkeyed, that detects edits and
deletions by anyone who does not recompute the chain — but an attacker with
write access to the database can read the stored hashes and rebuild it, which
verifies perfectly on its own.

Set `MOSIP_AUDIT_HMAC_SECRET` (property `mosip.security.audit-hmac-secret`,
minimum 16 characters — shorter values are **rejected at startup**, because a
guessable key produces keyed-looking hashes an attacker recomputes just as
easily) to key the hash with **HMAC-SHA-256** instead. The secret lives in the
app server's environment, not in the database, so a database-write attacker
cannot recompute a single link:

```bash
export MOSIP_AUDIT_HMAC_SECRET=$(openssl rand -hex 32)
curl -s localhost:8000/api/v1/config/audit/verify
# {"eventType":"CONFIG_CHANGED","chainedEntries":5,"headHash":"…","hmac":true,
#  "rotationWindowOpen":false,"retiredKeyHashes":false,"intact":true}
```

| `hmac` | meaning |
| ------ | ------- |
| `true` | Entries are HMAC-keyed. A database writer who edits rows cannot rebuild the chain; verification reports the first entry whose stored hash the secret does not produce. |
| `false` | **Documented fallback**: no secret configured, so the hash is plain SHA-256. Tamper-*detecting* only — a database writer can rebuild the trail. The mode is reported so this state is never mistaken for the keyed one. |

If a **keyed** chain needs to move to a new secret, set
`MOSIP_AUDIT_HMAC_SECRET` to the new value and
`MOSIP_AUDIT_HMAC_PREVIOUS_SECRET` to the old value. Writes use the new key;
verification accepts either. `/api/v1/config/audit/verify` reports
`rotationWindowOpen` while the previous key is configured and
`retiredKeyHashes` when any historical entries still need it. The console
shows both states in the chain badge and details.

The rows are immutable, so this repository does not re-key old entries. If
`retiredKeyHashes` is true, keep the previous secret available or the chain
will fail at its first old-key entry. Clearing the previous secret is safe only
when `retiredKeyHashes` is false. A future key retirement process needs a
separately trusted archive or re-key protocol; this overlap does not retire a
key that must still verify historical data.

The previous secret remains accepted by verification while configured, so
treat it as a live verification key. Anyone who obtains it can calculate
old-key HMACs outside the application. Do not use the overlap as a substitute
for protecting the secret.

Moving from the unkeyed SHA-256 fallback to HMAC is separate: the previous-key
setting cannot make old SHA-256 rows verify under HMAC. Those rows will report
as broken unless they are migrated through a trusted process. Keep the active
secret stable across restarts, and never fall back to unkeyed verification for
a chain that was written with HMAC.

The hash is computed **before** the INSERT: `id` is assigned by the database at
persist time and `createdAt` by `@PrePersist`, and the immutability triggers
reject the UPDATE that would write a corrected hash afterwards. `id` is
therefore deliberately excluded from the canonical form — position in the chain
plus `prev_hash` already binds an entry uniquely.

```json
{
  "eventType": "CONFIG_CHANGED",
  "createdAt": "2026-10-04T12:34:56.789Z",
  "details": {
    "workflowType": "OPERATOR",
    "action": "UPDATED",
    "actor": "key:9f2c1a7b4e0d",
    "changes": {
      "passiveThreshold": { "from": 0.80, "to": 0.93 },
      "maxRetryCount": { "from": 3, "to": 4 }
    },
    "risk": { "level": "LOW", "reasons": [] }
  }
}
```

- **Only fields that actually moved** are listed. A `PUT` that resubmits the
  current values is still recorded (someone touched the config) but with an empty
  `changes` object — a real move and a no-op must not read alike.
- **`action`** is `CREATED` when the `PUT` was the one that first created the
  `config_policies` row, otherwise `UPDATED`.
- **`actor`** is the username of the signed-in supervisor whose session made
  the write. No credential is stored, logged or echoed; the name is what makes
  an edit attributable to a person rather than to a shared secret, which is the
  reason the admin key was removed.
- **`risk`** classifies how much the edit weakens the checks: `HIGH` when it
  lowers `passiveThreshold` or switches `livenessEnabled` or
  `activeLivenessEnabled` off — the moves that silently defeat liveness — and
  `LOW` otherwise. `reasons` names which weakening move fired, so the console
  flags the edit without the reader diffing two numbers. The classification is
  computed over the diff and sealed inside the hashed `details`, so it cannot
  be softened after the fact without breaking the chain. Entries written
  before the classification existed simply have no `risk` key.
- Config events have **no session**, so they never appear in a session's
  `audit` trail, and that trail can never pick one up (`session_id IS NULL`).
- The feed is an open read, like `GET /api/v1/config/{workflowType}` (which
  already publishes the current policy). Only writes require a session, and only
  a supervisor's session at that.

ponytail: the feed is a bounded, newest-first read of one table with no
per-workflow server-side filter — the workflow type lives inside the JSON
`details` column, which is deliberately unqueryable (text, identical on H2 and  PostgreSQL). Filter client-side, as `static/console.js` does.

- `supportedChallengeTypes` must not be empty (and if `activeLivenessEnabled`, must have ≥ 1 type)

---

## Choosing `passiveThreshold` — calibrate, don't guess

A threshold number only means something relative to the **score distribution of
the scorer that produced the scores**. Two things changed to make the number
derivable instead of asserted:

1. **The score is a liveness confidence.** With `mosip.liveness.backend: auto`
   the passive score is MiniFASNet-V2's live-class probability (model
   confidence), not the old image-quality heuristic. That probability is
   badly calibrated — it pins at ~1.0 on a genuine face — so
   `mosip.liveness.score-temperature` (default `4.0`) compresses the log-odds
   above `0.80` into a readable band, anchored so `0.80` maps to itself and the
   default operating point does not move (see `ProbabilityCalibration`). Set it
   to `1` to score with the raw model confidence. A threshold chosen against
   one scorer (or one temperature) does not transfer to another — re-run the
   sweep after either change.
2. **The decision is a median over 5 scored frames**, not a single frame, so
   calibration must evaluate the same windowed decision (it does).

### The sweep endpoint

```
GET /api/v1/eval/threshold-sweep?targetBpcer=0.02
```

Returns one row per threshold (0.05 … 0.95, step 0.05):

| Field | Meaning |
|-------|---------|
| `bpcer` | fraction of bona-fide windows that would **escalate to an active challenge** at this threshold (user-experience cost) |
| `apcer` | fraction of attack windows that would **pass passive** at this threshold (security cost); `null` when unmeasured |
| `acer` | mean of the two; `null` when APCER is unmeasured |
| `recommendedThreshold` | the operating point (see rule below) |
| `recommendationBasis` / `note` | why, and what data was (not) available |

**Inputs:** bona-fide windows are the first `passiveMinFrames` scores of every
session that passed, taken from `frame_events` (real captures). Attack windows
are recorded presentation-attack sessions when any exist; otherwise a clearly
labelled **proxy corpus** (simulated print/screen degradations,
`attackDataIsProxy: true`). With neither, `apcer` is `null` and the note says
`APCER unmeasured` — never a fabricated zero.

**Recommendation rule:** the *highest* threshold that keeps
`BPCER ≤ targetBpcer` with `APCER = 0` (stricter is safer, so within the UX
budget we take the strictest point); failing that, minimum `ACER`; failing
that, no recommendation.

### Applying the result

```bash
# read the table
curl 'http://localhost:8000/api/v1/eval/threshold-sweep?targetBpcer=0.02'

# apply the recommended value per workflow (DB row wins at runtime)
# PUT requires a signed-in SUPERVISOR session (fail-closed: no session = 401,
# an operator session = 403). The session arrives as an HttpOnly cookie, so a
# cookie jar is all a command line needs:
curl -s -c jar.txt -X POST http://localhost:8000/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"$MOSIP_AUTH_BOOTSTRAP_USERNAME","password":"$MOSIP_AUTH_BOOTSTRAP_PASSWORD"}'
curl -X PUT http://localhost:8000/api/v1/config/RESIDENT \
     -b jar.txt \
     -H 'Content-Type: application/json' \
     -d '{"passiveThreshold": 0.90}'
```

Defaults are unified on `0.80` across `LivenessConfig`, `application.yml`, the
`config_policies` seed and the DB-miss fallback (V2 migration) — previously the
seed said 0.75 while everything else said 0.80. `targetBpcer` is the knob for
how much UX you spend: 0.02 means at most ~2% of genuine users are pushed into
an active challenge.

---

## Service knobs (`application.yml`, `mosip.liveness.*`)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `mosip.liveness.analysis-max-dimension` (`MOSIP_LIVENESS_ANALYSIS_MAX_DIMENSION`) | int | `1280` | Long-edge cap applied to every decoded frame **before** face detection, quality scoring and PAD (`ImageUtils.decodeBase64Frame` → `downscaleForAnalysis`), so a 1080p camera costs the analysis stages about what a 640×480 one does. A **cost bound, not a safety bound**: the 6 MB payload, 8192 px dimension and 40 M-pixel caps are applied first and are not configurable, and frames at or below the cap are never upscaled. Lowering it trades CPU for the detail detection and PAD see. Values below `64` stop the boot (the cascade's smallest model input is 60 px) instead of being clamped. It is **not score-neutral**: measured on one 720p frame (`docs/performance-baseline.md` §10), cap `1280` scores `0.9495` at `428.7 ms` per frame, cap `640` scores `0.6444` (below the `0.80` threshold, so the frame escalates instead of passing) at `151.4 ms`, and cap `480` finds no face at all. Nothing in the decision path reads this value — the frame's pixels simply change — which is why `1280` stays the default: it only means a 1080p camera costs the analysis stages what a 640×480 one does, and the browser console now captures at 640 px (`CAPTURE_MAX_EDGE` in `static/console.js`) regardless. |

---

## Running on 2 cores / 2 GB (`--lowmem`)

The target registration-client machine has **2 CPU cores, 2 GB of RAM and no
GPU**, on Windows, macOS or Linux. `./start.sh --lowmem` (or
`start.bat --lowmem`) turns on the whole recommendation — JVM flags plus the
`lowmem` Spring profile — and prints the exact command line it used. The same
state is reachable without the launcher: add the `lowmem` profile
(`--spring.profiles.active=dev,lowmem`) **and** the JVM flags below, because the
saving is not expressible in YAML.

Nothing here is applied without the flag. The default launch is the same command
it was before `--lowmem` existed: no flags, no `lowmem` profile, same defaults.

### Measured effect

Cold boots, five per side, **alternating baseline and tuned** (`A`, `B`, `A`, `B` …)
so that the shared host's drifting load lands on both sides equally; every boot
starts with the OpenCV native cache cleared and must report `engine: available`
before it is counted. Both JVMs are pinned to two cores (`taskset -c 0,1`) to
match the target. RSS is `VmRSS` from `/proc/<pid>/status` at that moment,
peak RSS is the JVM's own `VmHWM`, and "spring" is the application's own
`Started PadLivenessApplication in Ns` line.

| | baseline | `--lowmem` | Δ |
| --- | --- | --- | --- |
| boot to `/health`, median (min) | 23.1 s (21.2 s) | **19.0 s (18.0 s)** | **−4.1 s (−17.7 %)** |
| application startup (spring's line), median | 20.1 s | 17.1 s | −3.0 s (−14.9 %) |
| RSS at `/health`, median | 314.3 MB | **276.7 MB** | **−37.6 MB (−12.0 %)** |
| peak RSS (`VmHWM`), median | 314.3 MB | 276.7 MB | −37.6 MB |
| threads at `/health` | 38 | 31 | −7 |

All five tuned boots beat all five baseline boots on both axes (baseline
21.2–23.3 s and 304–340 MB; tuned 18.0–21.1 s and 266–281 MB), which is what makes
a difference this small defensible on a machine that is also running other work. A
second interleaved run of the same pair, taken earlier in the session while the
host was busier, agreed: 22.3 s → 19.0 s of boot and 320.6 MB → 274.4 MB of RSS,
again with every tuned boot beating every baseline boot on both axes.

**Without a trained archive** — the state a `--lowmem` launch is in until the
training step below is run — the launch still measured **22.3 s → 21.2 s** of boot
(−1.1 s, −4.9 %) and **319.6 MB → 279.9 MB** of RSS (−39.7 MB, −12.4 %) in its own
interleaved five-boot A/B, with the same 38 → 31 threads. Training the archive is
worth a further ~2.2 s of boot on top of that, and nothing else.

### Where the saving comes from

Each flag was then measured **on its own**, in its own interleaved A/B of five
boots per side, same method:

| change | boot to `/health` | RSS | kept |
| --- | --- | --- | --- |
| class-data-sharing archive (`-XX:SharedArchiveFile`) | −2.2 s (−10.4 %) | −11.4 MB | **yes** |
| `-XX:+UseSerialGC` | −0.9 s (−4.0 %) | **−41.2 MB (−12.9 %)** | **yes** |
| `-Xmx512m` | 22.2 s → 22.2 s (±0) | **−25.4 MB (−8.2 %)** | **yes** |
| `springdoc` API docs + Swagger UI off | 22.3 s → 22.2 s (14 paired boots: 6 wins, 5 losses, 3 ties) | 318 → 323 MB | no |
| `logging.level.root: WARN` + `spring.jpa.show-sql: false` | 22.2 s → 22.4 s | 318.3 → 323.1 MB | no |
| `server.tomcat.threads.max` 50 → 20 and `spring.datasource.hikari.maximum-pool-size` 5 → 2 | 22.2 s → 22.3 s | 329.3 → 323.3 MB (−1.8 %, inside the sample spread) | no |
| `-Xss512k` | no boot effect attributable (screened) | 325.4 → 327.9 MB — no saving (screened) | no |
| `spring.main.lazy-initialization: true` | −1 to −4 s | −36 MB | no — see below |

The kept flags do **not** add up: Serial GC and the heap cap both act on the same
heap footprint, so the combined RSS saving is smaller than the sum of the parts
(−78 MB on paper, −37 to −46 MB measured depending on how busy the host was).

Two rows are marked *(screened)*: `-Xss512k` and the Tomcat/Hikari pair were first
measured as five consecutive cold boots each against a control run the same way
(their RSS medians 327.9 MB, 295.1 MB and 308.1 MB against 325.4 MB). Both were
rejected on those numbers and then re-measured interleaved where it would have
mattered, which is the column above. Screening is enough to reject a change that
saves nothing or that the sample spread swallows; it is not enough to keep one,
which is why every kept flag has an interleaved pair.

### The JVM flags

- **`-Xmx512m`** caps the heap at 512 MB — a quarter of the machine — so
  metaspace (~34 MB measured), the code cache, 31 thread stacks and the OpenCV
  (~65 MB) and ONNX Runtime natives leave the operating system, and a database
  running beside the service, the majority of the 2 GB. Live use after a session
  resolved was 43 MB of the 121 MB the collector had grown to, so the cap is
  headroom, not a squeeze. At idle it measured −25.4 MB (−8.2 %); its real job is
  to stop the heap growing into memory the host does not have — a cap only shows
  up in RSS once the JVM would otherwise have used more, which is why one of the
  five screened boots saved only 2 MB.
- **`-XX:+UseSerialGC`** — on two cores the collector competes with the frame
  pipeline for the same CPUs, and the heap is small, so the serial collector's
  single-threaded young/mixed collections beat G1's region bookkeeping and
  concurrent marking threads. Measured: **−41.2 MB of RSS (−12.9 %)** and seven
  fewer threads (38 → 31), which is the collector's own thread pool. Note this is
  the collector the JVM picks by itself on a *one*-core machine; at two cores it
  defaults to G1, which is the wrong trade when RAM, not throughput, is the
  constraint.
- **class-data sharing (`-XX:SharedArchiveFile=target/lowmem-cds.jsa`)** — the
  service loads thousands of classes from its fat jar on every boot; the archive
  lets the JVM map parsed, verified class metadata instead of re-parsing it.
  Measured: **−2.2 s (−10.4 %)** of boot and −11.4 MB of RSS. It has to be
  **trained once**, because it is tied to the exact jar it was trained on (the JVM
  compares the jar's size and mtime — a rebuild invalidates it) and because the
  archive is written at JVM *exit*, which the launchers cannot guarantee: `start.sh`
  stops the service with a 4-second grace and `start.bat` uses `taskkill /F`, and
  both truncate the dump. So the launchers only ever *use* it — `start.sh` when the
  archive is newer than the jar, `start.bat` when it exists, having dropped it as
  it rebuilt — and print whether sharing is active or not. Training is left to
  this deliberate step:

  ```bash
  # once, on the machine (and with the JDK) that will run the service, after its
  # last build; the archive lands next to the jar it describes
  java -Xmx512m -XX:+UseSerialGC -XX:ArchiveClassesAtExit=target/lowmem-cds.jsa \
    -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
  # wait for http://localhost:8000/health, then stop it with Ctrl+C — a hard kill
  # writes no archive
  ./start.sh --no-build --lowmem      # now reports: class-data sharing: active
  ```

  **What the training costs, measured:** on this host the whole run took about
  five minutes — 33 s to reach `/health` and then ~4.4 minutes writing the 68 MB
  archive, during which the JVM does not exit (the script above waits for it; a
  launcher that stops the service after four seconds would truncate the dump,
  which is why the launchers do not try). The archive is written read-only
  (`0444`). The trade is therefore one-off against every boot: **~5 minutes once
  against 2.2 s per start**, which is worth it for a deployment that restarts the
  service — or for an image that can bake the archive in at build time — and not
  worth it for a single run.

  It lives under `target/` as a build artifact and is never committed, and it is
  platform-specific (a Windows archive is not valid on Linux). Pointing
  `-XX:SharedArchiveFile` at a missing or stale archive is a **warning, not a boot
  failure** — verified: the JVM logs `[warning][cds,dynamic] Unable to use shared
  archive` and starts without sharing — so a jar rebuilt after training can never
  fail to boot because of it, it just loses the saving until retrained.

For a deployment that does not use the launchers — a container image, systemd
unit or Kubernetes manifest — set the same three flags through the JVM options
your platform already exposes (for example
`JAVA_TOOL_OPTIONS="-Xmx512m -XX:+UseSerialGC -XX:SharedArchiveFile=/app/lowmem-cds.jsa"`)
and add `lowmem` to the active profiles. A container image is the best place to
train the archive: build it in a layer with the command above and have the
service start before that layer's image stops being writable, or bake the archive
in at build time — the runtime filesystem is usually read-only.

### The `lowmem` profile

`application-lowmem.yml` is where a *property* would go, and after measurement it
sets none: every Spring-side candidate either changed nothing measurable (the
Swagger/OpenAPI surface, Tomcat's thread ceiling, the Hikari pool, quieter
logging) or bought its boot-time win by deferring work. The file ships as the
extension point with those findings written down in it, so the next person to
propose a candidate can see what was already measured — and a deployment that
names profiles instead of flags can activate the mode without a nonexistent
profile.

**Lazy initialization is the one candidate that must not be used for this,**
even though it is the fastest. With `spring.main.lazy-initialization=true` the
boot no longer includes the dispatcher servlet, the entity manager or the schema
step; they run on the first request that needs them (visible in the log: the
`Initializing Spring DispatcherServlet` line moves to the first request's thread).
On a fresh database the service would therefore answer `/health` before a single
Flyway migration had run, and a schema or configuration error would surface as a
failed first frame instead of a failed boot. That is a behaviour change, not a
memory saving, so it is out — along with anything else that trades a startup
check for a runtime one. The OpenCV warm-up is unaffected either way: it is
started before the Spring context exists (`PadLivenessApplication`), which is why
`/health` keeps reporting `engine: available` in every configuration measured
here.

### What does not change

Frames were pushed through both launches — the same fixture, one session each and
up to 12 submissions in it, both JVMs pinned to two cores; the run stops when the
session resolves, which is frame 5 — and the decision sequence is identical: four
`retry_passive` frames and then `proceed`, with the same
`faceDetected=true`, `padFlag=false`, `padAttackType=null`,
`livenessScore=0.9139281719741627` and `faceQuality=0.8095082759857177` on every
frame, and the same session summary (`PASSED`, `finalResult=true`, 5 frames
evaluated). The heap stayed far under the 512 MB cap: serial GC's whole generation
set was 121 MB (37.5 MB young + 83 MB tenured, 43 MB of it live) with a 33.7 MB
metaspace, against 107.5 MB of G1 heap and **83.6 MB** of metaspace on the
baseline. That 50 MB of metaspace difference appears with the shared archive
present and is consistent with shared class metadata being accounted outside the
Metaspace pool, which would make it part of where the RSS saving comes from — the
measurement is the numbers above, the attribution is not separately verified. RSS
with frames processed: 335 MB tuned against 367 MB baseline.

### Reproducing a measurement

Any of the numbers above can be re-taken with the checked-in cold-boot gate, which
already clears the OpenCV native cache before each boot and refuses a boot whose
engine is missing:

```bash
# BOOT_MEDIAN_JAR=full measures the jar the launchers run (the gate's own
# default is the -Pslim jar); the environment override adds the JVM flags.
BOOT_MEDIAN_JAR=full BOOT_MEDIAN_PROFILE=dev,lowmem \
  JAVA_TOOL_OPTIONS="-Xmx512m -XX:+UseSerialGC" ./scripts/boot-median.sh
```

It reports the median only (`BOOT_MEDIAN_RUNS` sets the count, `BOOT_MEDIAN_MAX_SECONDS`
the budget) and does not read RSS, and it does not pin cores — verified as written,
it measured 29.2 s over two boots on this host, which is higher than the tables
above because the load was shared across four cores and both boots were cold. The
tables above used a harness that also pinned the JVM to two cores and sampled
`VmRSS`/`VmHWM` at `/health`, interleaving the two configurations so the host's
load drift hit both sides.

## Required environment variables

The application reads several environment variables for configuration. Secret values must be provided via secure mechanisms (e.g., Docker secrets, Kubernetes secrets, CI/CD masked variables) and **never** hard-coded in image or version control.

| Variable | Required? | Secret? | Description |
|----------|-----------|---------|-------------|
| `POSTGRES_USER` | no (defaults to `mosip`) | no | PostgreSQL username |
| `POSTGRES_PASSWORD` | **yes** | **yes** | PostgreSQL password |
| `POSTGRES_DB` | no (defaults to `pad_liveness`) | no | PostgreSQL database name |
| `POSTGRES_HOST` | no (defaults to `localhost`) | no | PostgreSQL host |
| `POSTGRES_PORT` | no (defaults to `5432`) | no | PostgreSQL port |
| `MOSIP_AUTH_BOOTSTRAP_USERNAME` | no (only on a database with no accounts) | no | First-supervisor bootstrap: the username to create. Must be 3–64 characters of letters, digits, dot, underscore or hyphen. Set with the password below or not at all — startup fails on one alone. |
| `MOSIP_AUTH_BOOTSTRAP_PASSWORD` | no (only on a database with no accounts) | **yes** | Password for that first supervisor, at least 12 characters. The account must change it at first sign-in. |
| `MOSIP_AUTH_TOKEN_TTL_SECONDS` | no (defaults to `3600`) | no | Session lifetime. Bounds how long a stolen token is usable. |
| `MOSIP_AUTH_MAX_FAILED_ATTEMPTS` | no (defaults to `5`) | no | Consecutive failed sign-ins before the account locks. |
| `MOSIP_AUTH_LOCKOUT_SECONDS` | no (defaults to `900`) | no | How long a lock lasts, stored as a timestamp so it expires by itself and a restart cannot clear it. |
| `MOSIP_AUTH_BCRYPT_COST` | no (defaults to `12`) | no | BCrypt cost (`log2` of the work per verification; `12` is ~300 ms on the 2-core target). Values below `10` are refused outside the `dev` profile. |
| `MOSIP_AUTH_COOKIE_SECURE` | no (defaults to `false`) | no | Send the session cookie only over HTTPS. Set `true` when TLS terminates in front of this service — a `Secure` cookie is dropped over plain HTTP, where the sign-in would appear to succeed and silently not. |
| `MOSIP_OPERATOR_API_KEY` | no (operator routes refuse everyone while it is blank) | **yes** | Operator API key (`X-Operator-API-Key`), which proves the `OPERATOR` role on the read-only operator feeds and on `GET /api/v1/auth/role`. Minimum 16 characters when set. It is never accepted on a privileged route: a leaked key can read feeds and cannot write a policy or an account. |
| `MOSIP_AUDIT_HMAC_SECRET` | **yes** (recommended for tamper‑evident audit chain) | **yes** | Secret for HMAC‑SHA‑256 audit chain; minimum 16 characters |
| `MOSIP_AUDIT_HMAC_PREVIOUS_SECRET` | no | **yes** | Previous audit HMAC secret for key rotation; must differ from current |
| `SERVER_ADDRESS` | no (defaults to `127.0.0.1`) | no | Socket address to bind. Loopback by default because the client-facing session routes are unauthenticated; the Docker images set `0.0.0.0`, and a host that needs LAN access must opt in deliberately. |
| `SERVER_PORT` | no (defaults to `8000`) | no | HTTP server port |
| `TOMCAT_THREADS_MAX` | no (defaults to `50`) | no | Maximum Tomcat threads |
| `DB_POOL_SIZE` | no (defaults to `5`) | no | HikariCP maximum pool size |
| `MAX_REQUEST_BODY_BYTES` | no (defaults to `25165824` = 24 MB) | no | Maximum HTTP request body size |
| `RATE_LIMIT_ENABLED` | no (defaults to `true`) | no | Enable rate‑limiting filter |
| `SESSION_CREATE_LIMIT` | no (defaults to `30`) | no | Session creations, **sign‑in attempts** (`auth-login`) **and operator‑route key attempts** per IP per minute (all use the per‑IP budget) |
| `FRAME_LIMIT` | no (defaults to `60`) | no | Frames + challenge validations per session per 10 s |
| `TRUSTED_PROXIES` | no (defaults to empty) | no | Comma‑separated list of trusted proxies for rate‑limiting |
| `MIN_CHALLENGE_WINDOW_MS` | no (defaults to `15000`) | no | Floor for per‑workflow challenge timeout (hard minimum 1000 ms) |
| `LIVENESS_DIAGNOSTICS_ENABLED` | no (defaults to `false`) | no | Enable diagnostic mode (loopback only) |

See `.env.example` in the repository root for a template.

### Who may do what (accounts, roles and the operator key)

The shared admin API key is gone. The privileged surface — `PUT
/api/v1/config/*`, `GET /api/v1/eval/threshold-sweep` and every route under
`/api/v1/users` — is reachable only by an account whose session proves the
`SUPERVISOR` role. The read-only operator feeds and the role probe still accept
the operator API key as well, so an existing read-only console keeps working.

**Getting the first supervisor.** On a database with no accounts, set
`MOSIP_AUTH_BOOTSTRAP_USERNAME` and `MOSIP_AUTH_BOOTSTRAP_PASSWORD` and start the
service: it creates one `SUPERVISOR` account that must change its password at
first sign-in. Both variables or neither — startup fails on one alone. Leaving
them set is a no-op from then on (the bootstrap does nothing when any account
exists), so it can never re-create an account, reset a password or grant a
privilege on a later boot. Two other states are checked at startup: a password
below the length floor and a username of the wrong shape are both refused, and a
deployment with no accounts and no bootstrap pair logs a warning that user
management is unreachable.

```bash
# sign in (the session comes back as an HttpOnly cookie, never in the body)
curl -s -c jar.txt -X POST localhost:8000/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"first-supervisor","password":"the-bootstrap-password"}' \
     | tee /dev/stderr
# → {"username":"first-supervisor","role":"SUPERVISOR","mustChangePassword":true,...}

# replace the bootstrap password (this revokes the session that did it and
# issues a replacement cookie in the same response)
curl -s -b jar.txt -c jar.txt -X POST localhost:8000/api/v1/auth/change-password \
     -H 'Content-Type: application/json' \
     -d '{"currentPassword":"the-bootstrap-password","newPassword":"a-longer-replacement"}'

# who am I, and which role did I prove
curl -s -b jar.txt localhost:8000/api/v1/auth/me
curl -s -b jar.txt localhost:8000/api/v1/auth/role
# → {"role":"SUPERVISOR"}

# the operator key answers the same probe, with the smaller role
curl -s localhost:8000/api/v1/auth/role -H "X-Operator-API-Key: $MOSIP_OPERATOR_API_KEY"
# → {"role":"OPERATOR"}

# sign out (idempotent, and clears the cookie)
curl -s -b jar.txt -X POST localhost:8000/api/v1/auth/logout
```

A non-browser client can carry the same session in `Authorization: Bearer
<token>` instead of the cookie; the token is the value from the `Set-Cookie`
header's `MOSIP_SESSION` cookie, and nothing else changes.

**Accounts.** A supervisor creates accounts with `POST /api/v1/users`, naming a
role of `SUPERVISOR` or `OPERATOR` and an initial password; the new account owes a
password change, so the value handed over out of band cannot become its permanent
password by inaction. Until that change is made, the session reaches
`GET /auth/me`, `POST /auth/change-password` and `POST /auth/logout` and is
refused with `403` everywhere else. `PUT /api/v1/users/{username}` changes a role,
disables or re-enables an account, or sets a new initial password (which revokes
the sessions the account holds — a reset that left the previous holder signed in
would not be a reset), and `DELETE /api/v1/users/{username}` removes one. A
change that would leave no enabled supervisor — a demotion, a disable or a delete
— is refused with `409`, because nobody would be left who may grant it back.

**Failures are one answer.** Every sign-in failure is the same `401` with the
same sentence, whether the username is unknown, the password wrong, the account
disabled or the account locked, so the endpoint is not a username oracle. An
unknown username still pays a full BCrypt verification, so it is not a timing
oracle either. The account locks for `MOSIP_AUTH_LOCKOUT_SECONDS` after
`MOSIP_AUTH_MAX_FAILED_ATTEMPTS` consecutive failures, and a success clears both
halves of the lock.

**Tokens.** 256 bits of CSPRNG output, handed over once in a `Set-Cookie` header
and stored only as a SHA-256 digest, so a database snapshot is not a set of usable
sessions. Signing out revokes; a password change revokes every other session and
mints a replacement; expiry is a column, not a job.

The operator key and every session token are compared in constant time and **fail
closed**: with `MOSIP_OPERATOR_API_KEY` blank no caller is ever answered
`OPERATOR` (an empty header does not match a blank key), and with no session and
no usable key the probe refuses everyone (`401`). The response carries the role
and nothing else — no credential, and not even a fingerprint of one — and a wrong
key is indistinguishable from a missing one. Key attempts on operator routes are
metered per client IP by the existing `RateLimitFilter` budget
(`SESSION_CREATE_LIMIT`), and sign-in attempts are metered on that same per-IP
budget, reported as the `auth-login` bucket, so guessing is throttled in both
cases.

## Audit logging security

The audit logging framework automatically replaces the values of any field keys that contain (case-insensitive) the substrings specified by the environment variable `AUDIT_REDACTION_KEYS` (a comma-separated list) with their SHA-256 hash. By default, the patterns are `"secret"`, `"key"`, `"token"`, `"password"`, and `"auth"`. For example, a call like `.field("apiToken", "abc123")` will store the value as a 64-character hexadecimal SHA-256 hash in the audit log, allowing later verification without storing the plaintext value.

Operators can extend the list of sensitive key patterns without code changes by setting the `AUDIT_REDACTION_KEYS` environment variable, e.g.:
```
export AUDIT_REDACTION_KEYS="secret,key,token,password,auth,private,credential"
```

Developers should still audit their usage of `AuditEvent.field()` to ensure no sensitive data is inadvertently added as a field value, but the automatic hashing provides an additional safety net while still allowing audit trail verification.

### Providing secrets securely

#### Docker Compose (development)

```yaml
services:
  api:
    # ...
    environment:
      POSTGRES_PASSWORD_FILE: /run/secrets/postgres-password
      MOSIP_AUTH_BOOTSTRAP_PASSWORD_FILE: /run/secrets/bootstrap-password
      MOSIP_AUDIT_HMAC_SECRET_FILE: /run/secrets/audit-hmac-secret
    secrets:
      - postgres-password
      - bootstrap-password
      - audit-hmac-secret

secrets:
  postgres-password:
    file: ./secrets/postgres-password.txt
  # Only needed on the first boot against an empty database; unset it (and the
  # username beside it) once the supervisor has signed in and replaced the
  # password. Leaving it set is a no-op rather than a backdoor, because the
  # bootstrap does nothing when any account already exists.
  bootstrap-password:
    file: ./secrets/bootstrap-password.txt
  audit-hmac-secret:
    file: ./secrets/audit-hmac-secret.txt
```

#### Kubernetes (production)

Create secrets:

```bash
kubectl create secret generic db-credentials \
  --from-literal=POSTGRES_PASSWORD=<password> \
  --from-literal=POSTGRES_USER=mosip \
  --from-literal=POSTGRES_DB=pad_liveness \
  --from-literal=POSTGRES_HOST=postgres \
  --from-literal=POSTGRES_PORT=5432

kubectl create secret generic mosip-secrets \
  --from-literal=MOSIP_AUTH_BOOTSTRAP_USERNAME=<first-supervisor> \
  --from-literal=MOSIP_AUTH_BOOTSTRAP_PASSWORD=<first-supervisor-password> \
  --from-literal=MOSIP_AUDIT_HMAC_SECRET=<audit-secret> \
  --from-literal=MOSIP_AUDIT_HMAC_PREVIOUS_SECRET=<previous-audit-secret>
```

Reference them in the pod spec:

```yaml
env:
  - name: POSTGRES_PASSWORD
    valueFrom:
      secretKeyRef:
        name: db-credentials
        key: POSTGRES_PASSWORD
  - name: MOSIP_AUTH_BOOTSTRAP_USERNAME
    valueFrom:
      secretKeyRef:
        name: mosip-secrets
        key: MOSIP_AUTH_BOOTSTRAP_USERNAME
  - name: MOSIP_AUTH_BOOTSTRAP_PASSWORD
    valueFrom:
      secretKeyRef:
        name: mosip-secrets
        key: MOSIP_AUTH_BOOTSTRAP_PASSWORD
  - name: MOSIP_AUDIT_HMAC_SECRET
    valueFrom:
      secretKeyRef:
        name: mosip-secrets
        key: MOSIP_AUDIT_HMAC_SECRET
```

> 💡 **Tip**: For added security, consider using a secrets management solution like HashiCorp Vault, AWS Secrets Manager, or Azure Key Vault and inject values at runtime via init containers or sidecars.

---

| Key | Default | Description |
|-----|---------|-------------|
| `backend` | `auto` | Single selection for **both** wirings — the HTTP scorer (`PassiveScoringService`) and the `LivenessBackend` SPI bean (interop report F5): `auto` = bundled MiniFASNet ONNX when it loads, heuristic fallback otherwise (SPI bean: the scripted mock); `heuristic` = force the OpenCV quality heuristic (SPI bean: the mock); `mock` / `onnx-minifasnet-v2` / `mediapipe-facemesh` / `tflite-minifasnet` = that backend by its audit id on **both** paths. Explicit ids never fall back to another scorer — unavailable means a coded, fail-closed error (the engine maps it to its device error). Unknown values fail at startup. |
| `model-path` | *(blank)* | Filesystem override for the model; blank uses the bundled `classpath:models/minifasnet_v2.onnx` (SHA-256 `d7b3cd9b…` verified on load). |
| `score-temperature` | `4.0` | Log-odds temperature that de-saturates the model's live-class probability **above** `0.80` (`ProbabilityCalibration`). The raw MiniFASNet score sits at 0.99–0.9999 on an ordinary genuine face, so the console showed a flat `1.000` with no information. The map is anchored at the default threshold (0.80 → 0.80), so the pass/fail verdict at `t ≤ 0.80` is unchanged; `1` reports the raw model confidence and `>1` spreads more. Per-workflow thresholds above 0.80 are applied to the calibrated scale and therefore demand a slightly higher raw confidence. |
| `onnx-intra-op-threads` | `0` | Threads one ONNX inference may use (`intraOpThreads` backend option). `0`/blank derives it from the core count as `max(1, cores/2)` — **one thread on the 2-core target hardware**, where the second core must stay free for the next frame's decode, quality check and Haar detection. Measured on a 4-core host: 1 thread 49.5 ms per distinct frame, 2 threads 54.1 ms, 4 threads 123.3 ms (`docs/performance-baseline.md` §9), and the value changes no score (`OnnxMiniFasNetBackendTest.intraOpThreadCountDoesNotMoveAnyValue`). `0` derives; a negative value stops startup with a message naming the key, and a non-integer stops the boot at Spring's binding — neither silently defers to ORT's all-cores default. The backend option itself accepts only a positive integer, so a direct SPI user gets the same refusal. |
| `passive-threshold` | `0.80` | Mirrors `LivenessConfig.DEFAULT_PASSIVE_THRESHOLD`; the `config_policies` DB row wins at runtime. Also the anchor of `score-temperature`'s compression, so that default is preserved exactly. |
| `min-face-quality`, `passive-min-frames`, `passive-window-frames`, `min-challenge-count`, `challenge-timeout-ms`, `max-retries` | see yml | Engine defaults for the embedded (library) path. |
| `diagnostics-enabled` (`LIVENESS_DIAGNOSTICS_ENABLED`) | `false` | **Diagnostic mode (opt-in, local)** — orchestration spec §10. When true, the service retains raw per-frame scores, timings, FPS and the scorer delegate in a bounded ring (**no pixels**) and `GET /api/v1/diagnostics` serves the snapshot to **loopback callers only**; disabled or remote callers get the identical empty 404, so the mode never leaks its own existence. The console's debug panel appears only when both gates pass. |

---

## Per-Workflow Policy Overrides

Each workflow (RESIDENT_REGISTRATION, OPERATOR_AUTH, SUPERVISOR_AUTH) can override any subset of the base config via `LivenessPolicy`:

```java
LivenessConfig config = LivenessConfig.builder()
    .passiveThreshold(0.80)
    .workflowOverrides(Map.of(
        WorkflowType.SUPERVISOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.92)       // stricter for supervisors
            .withMinChallengeCount(3),         // more challenges required
        WorkflowType.OPERATOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.85)
            .withActiveLivenessEnabled(false)  // skip active for operators
    ))
    .build();
```

Any field not overridden inherits the base value. The merge happens in `LivenessConfig.effectivePolicy(workflow)`.

---

## Repeated Failure Actions

What the session does once the active-challenge retry budget (`maxRetryCount`)
is exhausted. **All three are terminal and none grants another challenge** — extra
attempts belong in `maxRetryCount`, not in the failure action. They differ only in
the outcome signal and whether the client may open a fresh session. Wired into the
HTTP decision path (`DecisionEngineService.processChallengeValidation`), so a
per-workflow value is real behaviour, not just stored config.

| Action | Session | `action` returned | `failureReason` | `mayRetrySession` |
|--------|---------|-------------------|-----------------|-------------------|
| `LOCK_OUT` | FAILED | `locked` | `max_retries_exceeded:locked_out` | `false` |
| `ESCALATE_TO_OPERATOR` | FAILED | `escalate_to_operator` | `max_retries_exceeded:escalation_required` | `false` |
| `FALLBACK` (seeded as `ALLOW_RETRY`) | FAILED | `failed` | `max_retries_exceeded` | `true` |

`escalate_to_operator` is a signal only (there is no escalation endpoint of its
own); the client routes the subject to an operator.

The FastAPI backend ([`pad_liveness_backend`](../pad_liveness_backend/README.md))
reports this same vocabulary for the same policy — `lock` / `escalate` /
`allow_retry` there, with identical `action`, `failureReason` and
`mayRetrySession` values — so one client can read either backend.

---

## Per-user-type policy at session start

The user type (`RESIDENT` / `OPERATOR` / `SUPERVISOR`) is chosen **once**, when the
session is created. The server resolves that workflow's effective policy, validates
it, and **freezes it on the session** (`policy_snapshot`). The decision engine then
uses the frozen snapshot for the whole session, so an admin edit to
`config_policies` cannot change an in-flight session's operating point. Legacy rows
with no snapshot fall back to a live read.

The three workflows seed **distinct** operating points (single source of truth:
`WorkflowPolicyDefaults`, mirrored by the `V4__session_policy_snapshot.sql` seed and
the `ConfigController` lazy-create path):

| Workflow | `passiveThreshold` | `minChallengeCount` | `challengeTimeoutMs` | `maxRetryCount` | `onRepeatedFailure` | challenges |
|----------|--------------------|---------------------|----------------------|-----------------|---------------------|------------|
| `RESIDENT` | 0.80 | 1 | 20000 | 3 | `ESCALATE_TO_OPERATOR` | blink, smile |
| `OPERATOR` | 0.82 | 1 | 15000 | 2 | `FALLBACK` | blink, turn_left, turn_right |
| `SUPERVISOR` | 0.85 | 2 | 15000 | 1 | `LOCK_OUT` | blink, smile, turn_left, turn_right |

The names in that column are `RepeatedFailureAction` — the **terminal action**
vocabulary, and what these seeds are written in
(`WorkflowPolicyDefaults`). The REST field of the same name takes the
`FailurePolicy` spelling instead: `LOCK`, `ESCALATE`, `ALLOW_RETRY`. The two
vocabularies are one-to-one (`LOCK_OUT` ↔ `LOCK`, `ESCALATE_TO_OPERATOR` ↔
`ESCALATE`, `FALLBACK` ↔ `ALLOW_RETRY`), which is why a `PUT` writes
`ALLOW_RETRY` and the session that spends its budget then reports the `FALLBACK`
action — see the table above.

A resident may be physically assisted by an officer, so a failed resident check
escalates to an operator; operator/supervisor authentication must never escalate to
an operator, so those workflows fail/retry instead. All values are defaults —
override any field at runtime with `PUT /api/v1/config/{workflowType}`.

```bash
# The session-create response carries the resolved, frozen policy:
curl -s -X POST localhost:8000/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"workflowType":"SUPERVISOR","deviceId":"L1-CAM-01"}' | python3 -m json.tool
```

> Live preview vs frozen snapshot: `GET /api/v1/config/{workflowType}/effective`
> shows what a **new** session would get; a session's own `policy` field shows what
> that **in-flight** session is actually using.

---

## Behavior on Failure

| Condition | User Message (generic, never reveals detection method) | Retryable? |
|-----------|--------------------------------------------------------|------------|
| Face not detected | "No face detected. Please look at the camera." | Yes |
| Multiple faces | "Multiple faces detected. Only one person may be in the frame." | Yes |
| Poor face quality | "Face is not clear. Please improve lighting and hold still." | Yes |
| Liveness below threshold (active disabled) | "Liveness check incomplete. Please continue looking at the camera." | No (hard fail) |
| PAD failure | "Face verification could not be completed. Please try again." | **No** when the workflow has no active challenge (terminal, distinct audit); otherwise the confirmed hit escalates to the challenge, where PAD stays terminal |
| Challenge failure | "Verification action was not completed. Please try again." | Yes (retry) |
| Challenge timeout | "Verification timed out. Please try again." | Yes (retry) |
| Max retries exceeded (`LOCK_OUT`) | "Face verification failed. Please contact an operator for assistance." | No |
| Max retries exceeded (`ESCALATE_TO_OPERATOR`) | "Face verification could not be completed. An operator will assist you." | No (operator assist) |
| Max retries exceeded (`FALLBACK`/`ALLOW_RETRY`) | "We could not verify face liveness. Please try again." | Yes — a **new** session (`mayRetrySession: true`) |
| Device unavailable | "Biometric device is unavailable." | No |

---

## Example: Hardened Supervisor Configuration

```java
LivenessConfig hardened = LivenessConfig.builder()
    .passiveThreshold(0.90)
    .minFaceQuality(0.60)
    .passiveMinFrames(7)
    .passiveWindowFrames(10)
    .minChallengeCount(3)
    .maxRetries(1)
    .challengeTimeoutMs(8_000)
    .onRepeatedFailure(RepeatedFailureAction.LOCK_OUT)
    .supportedChallengeTypes(EnumSet.of(
        ChallengeType.BLINK,
        ChallengeType.SMILE,
        ChallengeType.TURN_HEAD_LEFT,
        ChallengeType.TURN_HEAD_RIGHT,
        ChallengeType.LOOK_DIRECTION))
    .workflowOverrides(Map.of(
        WorkflowType.SUPERVISOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.95)
            .withMinChallengeCount(4)
            .withMaxRetries(0)  // no retries for supervisors
    ))
    .build();
```

---

## Configuration Loading

In production, configuration should be loaded from a signed, integrity-verified config file (e.g., JSON or properties). The `LivenessConfig.Builder` can be populated programmatically:

```java
LivenessConfig config = LivenessConfig.builder()
    .passiveThreshold(Double.parseDouble(props.getProperty("liveness.passive.threshold", "0.80")))
    .minChallengeCount(Integer.parseInt(props.getProperty("liveness.active.minChallenges", "2")))
    .build();
```

Config updates may sync when the device is online; the sync must use integrity verification (e.g., signed manifests) to prevent tampering.
