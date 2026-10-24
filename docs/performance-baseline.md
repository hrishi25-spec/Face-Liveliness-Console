# Performance baseline — packaged service on this machine

**Measured:** 2026-10-13 · **Scope:** measurement only. No source, configuration,
threshold or test was changed to produce this document — it is the only file added.
Every number below was measured on the host described in §2 with the commands in
§3; nothing here is estimated or copied from another machine.

The point of this file is repeatability. It records what the service costs on a
small CPU-only workstation, so the next change can be compared against a number
rather than an impression. It is a dated measurement artifact, not a contract:
[PRD §6](PRD.md) holds the budgets, [backend-interoperability-report.md §10](backend-interoperability-report.md)
holds the SPI-level latency table this one extends.

---

## 1. What was measured, and what "before" means

| Metric | Budget / previous record | Source of the "before" figure |
| --- | --- | --- |
| Cold boot to `/health` | ≤ 120 s median (5 cold-cache boots) | PRD §6 NFR-2 · CI job `boot-time` · `scripts/boot-median.sh` |
| Idle RSS | no budget recorded | — (new measurement) |
| RSS after 100 frames | no budget recorded | — (new measurement) |
| Per-frame latency (SPI, 100 frames) | no budget; recorded 2026-10-07 | backend-interoperability-report.md §10 |
| Jar size (full) | ≤ 245 MiB | PRD §6 NFR-3 · `scripts/check-jar-size.sh` |
| Jar size (slim) | ≤ 110 MiB | PRD §6 NFR-3 · `scripts/check-jar-size.sh` |
| CPU over a 30 s capture window | no budget recorded | — (new measurement) |
| Throughput | "a few frames per second per session is enough" | PRD §6 NFR-5 |

Because this task changed no code, the "previously recorded" column is the
comparable figure already in the repository, not an earlier run of this same
benchmark. Where a previous figure exists the two columns should agree — that
agreement (§6) is the evidence that the measurement reproduces.

## 2. Host under test

| | |
| --- | --- |
| CPU | Intel Core i3-1005G1 @ 1.20 GHz — 2 physical cores, 4 hardware threads (`nproc` = 4), running at **~38 % of rated clock** during the run (idle-scaling governor) |
| RAM | 7 714 MiB total |
| OS | Arch Linux, kernel 7.2.9-zen1-1-zen, x86_64 |
| JVM | OpenJDK 17.0.20.1 (build 17.0.20.1+1), no JVM flags passed |
| Profile | `dev` — H2 in-memory database, `spring.jpa.show-sql: true`, Flyway disabled |
| Backend | `mosip.liveness.backend: auto` → **ONNX MiniFASNet-V2** (the diagnostics endpoint reports that delegate, see §5.3) |
| Input | `src/test/resources/fixtures/real-face.jpg` (30 314 bytes), the same fixture `BackendPerFrameLatencyTest` and §10 use. No new or real biometric data is added by this document. |

**Target hardware is 2 CPU cores / 2 GB RAM.** This host has the same physical
core count but 3.8× the RAM and no memory cap, so RSS figures here are *not* a
2 GB deployment's figures (§7). Clock scaling means the timings are pessimistic
relative to a machine held at full clock.

## 3. Build and setup

Each profile is built with its own `clean`, exactly as CI does — the slim profile
unpacks platform natives into `target/classes`, so a non-clean build can leave
linux-only natives in the full jar (see the comment in `.github/workflows/ci.yml`).

```bash
# full (cross-platform) jar
./mvnw --batch-mode clean package -DskipTests
cp target/pad-liveness-backend-*.jar /tmp/pad-full.jar      # keep it: the slim build reuses target/

# slim (linux-x86_64) jar — the one CI, Docker and deployments run
./mvnw --batch-mode clean package -DskipTests -Pslim
```

> **Careful when reproducing:** after the slim build, `target/pad-liveness-backend-<version>.jar`
> is a **34.7 MiB stub** without dependencies (only `…-slim.jar` is a fat jar).
> `scripts/check-jar-size.sh` would happily report "34 MiB / 245 MiB budget" for it.
> Pass the preserved copy or the `-slim.jar` glob, as §4 does.

## 4. Results

| Metric | Previously recorded / budget | Measured 2026-10-13 | Verdict |
| --- | --- | --- | --- |
| Cold boot to `/health` (median of 5 cold-cache boots) | ≤ 120 s budget; ~43 s observed | **58.3 s** (5 samples: 71.31, 55.31, 58.29, 56.39, 58.98 s; Spring reports 49.2–64.3 s) | within budget |
| Cold boot, single boots (wall to `/health`) | — | 55.20, 55.79, 55.69 s (three further boots) | 8 boot samples total, median 56.1 s |
| Idle RSS (java process, after 30 s settle) | — | **353 021 kB ≈ 345 MiB** (median of 12 samples over 60 s; intra-run drift 76 kB) | new |
| Idle RSS, cross-boot spread | — | 334 616 / 352 988 / 419 404 kB (327 / 345 / 410 MiB) on three separate boots | see §7 |
| RSS after 100 frames | — | 341 032 → **405 448 kB** (333 → 396 MiB, **+62.9 MiB**) | new |
| RSS after the 30 s capture window | — | 413 740 kB ≈ 404 MiB | new |
| Per-frame latency, ONNX backend (SPI, 100 frames, single thread, decode excluded) | 156.768 ms mean · p95 206.396 ms (2026-10-07) | **160.899 ms mean · p50 156.615 · p95 209.316 · max 226.243 ms · 6.2 fps** | +2.6 % vs record |
| Per-frame latency, mock backend | 0.017 ms mean (2026-10-07) | 0.012 ms mean · p95 0.029 ms · ≈81 670 fps | noise-dominated (§7) |
| Per-frame latency, HTTP path (`POST /frames`, 100 passive frames) | — | client mean **0.338 s** · p50 0.303 · p95 0.425 · p99 0.561 s (first frame 2.288 s; excluding it: mean 0.318 · p95 0.422 s) | new |
| Server-side pipeline means over those 100 frames | — | decode 8.78 · face detect 54.15 · onnx score 103.94 · pad heuristic 3.35 · pad onnx 95.24 · **total 304.71 ms** (p99 478.15, max 2 071.16) | new |
| CPU over a 30 s capture window | — | **50.66 CPU-seconds for 78 passive frames in 30.13 s** = 1.68 cores (168 % of one core, 42 % of all 4 hardware threads), 2.59 frames/s | see §5.4 |
| CPU per frame | — | 0.70 CPU-s/frame (100-frame run) · 0.65 CPU-s/frame (30 s window) | new |
| Jar size, full | ≤ 245 MiB | **210 395 658 bytes = 200.65 MiB** | within budget |
| Jar size, slim | ≤ 110 MiB | **93 761 602 bytes = 89.42 MiB** | within budget |

> **Superseded on 2026-10-13:** the two per-frame rows (SPI, HTTP) and the
> server-side phase means above were taken before the duplicate-detection fix
> described in §9. §9 re-measures them on this host with the same harness; the
> boot, RSS, jar-size and CPU rows are unaffected by that change and stand as
> measured.
>
> **Cold boot re-measured 2026-10-17:** the same `./scripts/boot-median.sh` on the
> same host, with the native cache cleared before every run, reported **15.8 s
> median** (16.66, 15.69, 15.76, 15.67, 15.75 s; Spring 13.7–14.9 s) against the
> 120 s budget. The 58.3 s run above was taken while the machine was under load, so
> the two are the same metric under different contention, not a speed-up. The jar
> sizes in this table were re-confirmed the same day: 200 MiB full, 89 MiB slim.

## 5. How each number was taken

### 5.1 Cold boot (5 cold-cache boots, CI parity)

```bash
BOOT_MEDIAN_RUNS=5 BOOT_MEDIAN_MAX_SECONDS=120 ./scripts/boot-median.sh
# → median cold boot: 58.3 s over 5 runs (budget 120 s); per-run logs in /tmp/boot-median
#   On an idle host the same command reports ~16 s — see the re-measurement note in §4.
```

"Cold" means the extracted OpenCV native cache (`/tmp/opencv_openpnp*`) is deleted
before every boot, so each run pays native extraction — the case a fresh deployment
hits. The script also fails a boot that comes up without `"engine":"available"`,
which would otherwise be *faster* and drag the median down. Wall clock is
cross-checked against Spring's own `Started PadLivenessApplication in … seconds`.

### 5.2 Idle RSS, and RSS after 100 frames

The session state machine accepts exactly **5 frames per session**
(`mosip.liveness.passive-min-frames`) before the passive verdict closes it to
further frames with `409`. Measured directly: frames 1–4 answer
`{"action":"retry_passive"}`, frame 5 answers `{"action":"proceed"}`, frames 6–7
answer `409 CONFLICT`. A 100-frame workload is therefore 20 sessions × 5 frames —
what a workstation doing 20 captures actually costs.

Boot the packaged jar (a namespaced port variable matters: an ambient `PORT=0`
makes Spring bind an ephemeral port and every poll below would miss it):

```bash
PERF_PORT=8092
java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT-slim.jar \
  --spring.profiles.active=dev --server.port=$PERF_PORT \
  --logging.level.com.zaxxer.hikari=DEBUG > /tmp/perf-app.log 2>&1 &
APP=$!
until curl -sf http://127.0.0.1:$PERF_PORT/health; do sleep 1; done   # expect "engine":"available"

# idle RSS: settle 30 s, then sample every 5 s for 60 s
rss_kb() { awk '/^VmRSS:/{print $2}' /proc/$APP/status; }
sleep 30; for i in $(seq 1 12); do rss_kb; sleep 5; done

# 100 frames: 20 sessions x 5 frames
base64 -w0 src/test/resources/fixtures/real-face.jpg > /tmp/frame.b64
printf '{"frameBase64":"%s"}' "$(cat /tmp/frame.b64)" > /tmp/frame.json
for s in $(seq 1 20); do
  SID=$(curl -s -X POST http://127.0.0.1:$PERF_PORT/api/v1/sessions \
        -H 'Content-Type: application/json' \
        -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' \
        | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  for f in 1 2 3 4 5; do
    curl -s -o /dev/null -w '%{time_total} %{http_code}\n' -X POST \
      http://127.0.0.1:$PERF_PORT/api/v1/sessions/$SID/frames \
      -H 'Content-Type: application/json' --data-binary @/tmp/frame.json
  done
done
```

Frame counts are confirmed server-side rather than trusted from the client: the
`decode` and `onnxscore` counters in `GET /api/v1/metrics` each advanced by
**exactly 100** during the workload (all 100 responses were `200`).

### 5.3 Per-frame latency and the pipeline breakdown

```bash
./mvnw --batch-mode test -Dtest=BackendPerFrameLatencyTest
cat target/backend-latency-results.txt     # SPI-level numbers in §4
curl -s http://127.0.0.1:$PERF_PORT/api/v1/metrics | grep -o '"pipelineTimings":{.*}'
```

**Which scorer actually ran is reported, not inferred.** `GET /api/v1/diagnostics`
(opt-in and loopback-only) reports the delegate behind the score —
`DiagnosticsService` records `PassiveScoringService.scorerId()`, which answers the
model id once a model is loaded and `opencv-heuristic` otherwise:

```bash
LIVENESS_DIAGNOSTICS_ENABLED=true java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT-slim.jar \
  --spring.profiles.active=dev --server.port=$PERF_PORT > /tmp/perf-scorer.log 2>&1 &
# … create one session and submit two frames (§5.2), then:
curl -s http://127.0.0.1:$PERF_PORT/api/v1/diagnostics | tr ',' '\n' | grep -iE 'scorer|score|frameCount'
# "scorer":"onnx-minifasnet-v2"   "frameCount":2   "lastScore":0.9139281719741627
```

So the passive figures above are the ONNX path and not the quality heuristic.
Independently, the `onnxscore` and `paddonx` phases counted 100 over the same 100
frames. The phase sum (265.4 ms) sits 39 ms under the `total` mean (304.7 ms) — the
part of the request outside the pipeline: DB round trip, entity saves, audit writes
and the rate limiter, consistent with the comment on `PipelineTimers.TOTAL` in
`FramesController`.

One caveat belongs with that evidence, because it limits what the counters prove:
under `auto`, `PassiveScoringService.score` still falls back to the quality heuristic
**per frame** (the model level sees no face or more than one, or inference throws)
while that frame is still timed in the `onnxscore` phase. The phase counters therefore
show the ONNX branch was taken and the delegate shows the model was the active scorer;
neither shows that every individual frame's number came from the model.

### 5.4 CPU over a 30 s capture window

```bash
cpu_ticks() { awk '{print $14+$15}' /proc/$APP/stat; }   # utime+stime, CLK_TCK=100
W0=$(date +%s%3N); C0=$(cpu_ticks); END=$((W0+30000))
while [ "$(date +%s%3N)" -lt "$END" ]; do
  SID=$(curl -s -X POST http://127.0.0.1:$PERF_PORT/api/v1/sessions \
        -H 'Content-Type: application/json' \
        -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' \
        | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  for f in 1 2 3 4 5; do
    curl -s -o /dev/null -X POST http://127.0.0.1:$PERF_PORT/api/v1/sessions/$SID/frames \
      -H 'Content-Type: application/json' --data-binary @/tmp/frame.json
    [ "$(date +%s%3N)" -ge "$END" ] && break
  done
done
echo "$(( $(cpu_ticks) - C0 )) cpu-ticks over $(( $(date +%s%3N) - W0 )) ms"
```

Result: **16 sessions, 78 frames, all `200`, 5 066 ticks = 50.66 CPU-seconds over
30.13 s**. One sequential client therefore holds ~1.7 cores on this host: the frame
cost is parallelised *inside* the request (ONNX Runtime intra-op threads and OpenCV
both use the available cores), so a single capture is not a single-core workload.
Zero rate-limit rejections were recorded (`rateLimitedRequests: 0`), so the limiter
did not shape this run: the engine's own speed did (2.59 frames/s passive).

### 5.5 JVM flags, Tomcat threads and Hikari pool actually in use

```bash
ps -o pid=,rss=,args= -p $APP          # launch line: no JVM flags
jcmd $APP VM.flags | tr ' ' '\n' | grep -E '^-XX:\+?[A-Z]' | sort
grep -o 'HikariPool-1 - Pool stats ([^)]*)' /tmp/perf-app.log | tail -3
grep -o 'HikariPool-1 - After cleanup  stats ([^)]*)' /tmp/perf-app.log | sort -u
jcmd $APP Thread.print | grep -o 'http-nio-[0-9]*-exec-[0-9]*' | sort -u | wc -l
awk '/^Threads:/{print $2}' /proc/$APP/status
```

| | Value in use | Source |
| --- | --- | --- |
| JVM flags | **none passed.** Effective: `-XX:+UseG1GC`, `-XX:MaxHeapSize=2023751680` (1.88 GiB = 25 % of 7 714 MiB), `-XX:InitialHeapSize=127926272` (122 MiB), `-XX:MaxNewSize=1214251008`, `-XX:SoftMaxHeapSize=2023751680`, `-XX:ReservedCodeCacheSize=251658240`, `-XX:CICompilerCount=3`, `-XX:G1ConcRefinementThreads=4`, `-XX:+UseCompressedOops`, `-XX:+UseCompressedClassPointers` | `ps` launch line + `jcmd VM.flags` |
| Container profile (not used in these runs) | `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"` | Dockerfile line 8 |
| Tomcat threads | configured max **50** (`server.tomcat.threads.max`, `TOMCAT_THREADS_MAX` unset); **10** worker threads ever created under this load, 39–40 JVM threads idle, 44 at peak | application.yml + `jcmd Thread.print` + `/proc/<pid>/status` |
| Hikari pool | configured **`maximum-pool-size: 5`** (`DB_POOL_SIZE` unset); **in use: peak 2 connections, peak 1 active**, 1 idle connection at rest | application.yml + Hikari's own DEBUG statistics log |

Hikari's own lines, verbatim from the run: `HikariPool-1 - Added connection conn0`
… `HikariPool-1 - After cleanup  stats (total=2, active=1, idle=1, waiting=0)`. The
pool is demand-driven: with zero traffic the stats read `total=1, active=0`.

## 6. Reproducibility: do the commands give the same answer?

| Measurement | Recorded value | Repeat | Delta | Within 15 %? |
| --- | --- | --- | --- | --- |
| Cold boot | 58.3 s (median of the 5-run CI-parity run) | 8 boots measured in total: seven of them 55.20–58.98 s, the first-ever boot 71.31 s; median of all 8 = 56.1 s | −3.8 % vs the 5-run median | yes (the one outlier is +22 %) |
| ONNX per-frame mean | 156.768 ms (repo record, 2026-10-07) | 160.899 ms today | +2.6 % | yes |
| Idle RSS | 353 021 kB | 12 samples in-run: 352 988–353 064 kB | ±0.02 % in-run | yes within a boot; **no** across boots (327/345/410 MiB, see §7) |
| Frame count for the 100-frame workload | 100 | `decode`/`onnxscore` counters both +100; 100/100 responses `200` | exact | yes |
| Jar size | 200.65 / 89.42 MiB | rebuilt the same way; `check-jar-size.sh` agrees | exact | yes |

The one figure that does **not** reproduce within 15 % is the mock backend's
per-frame mean (0.012 ms today vs 0.017 ms on record, −29 %). That value is
sub-microsecond and dominated by host frequency/cache state rather than by backend
code — a caveat §10 of the interoperability report already records.

## 7. Limitations — read before quoting these numbers

1. **`dev` profile, in-memory H2.** The RSS figures include an in-memory database
   with `show-sql: true` logging every statement to stdout, and they include the
   `audit_logs` / `frame_events` rows the run created. PostgreSQL on a real disk is
   not represented; partitioning the growth between "engine" and "in-memory DB"
   was out of scope for this measurement.
2. **RSS is not a steady number.** Every JVM heap figure moves as G1 commits and
   uncommits regions. Within one boot it was stable to 76 kB over 60 s, but three
   separate boots read 327 / 345 / 410 MiB under identical conditions, so treat
   345 MiB as a typical idle figure with a ±20 % boot-to-boot band, not as a
   guarantee. Report the method, not a single reading.
3. **This host is not the deployment target.** 7 714 MiB of RAM with no cap and a
   1.88 GiB default heap says nothing about a 2 GB, `MaxRAMPercentage=50` container.
   The CPU numbers are pessimistic in a different direction: the governor held the
   package at ~38 % of rated clock.
4. **One input, one workflow.** Every frame was the same genuine-face fixture in
   `RESIDENT`. Haar cascade and ONNX cost are content- and resolution-dependent
   (the pipeline caps analysis at 1280 px, PRD NFR-6), and a print/screen/video
   attack takes a different path through PAD.
5. **A capture session is 5 frames here.** Because the fixture passes passively,
   the session closes after `passive-min-frames`; the 30 s window therefore chains
   16 sessions. A session that escalates to an active challenge lasts longer but
   costs less per frame: the challenge path records `decode` + `facedetect` +
   `padheuristic` only, and *no* ONNX scoring (verified on the exploratory run:
   `onnxscore` advanced by exactly its 15 passive frames and by nothing else,
   while `decode` counted 591 — those same 15 passive frames plus 576 challenge
   frames).
   Quote the passive figures as "per frame"; do not apply them to challenge frames.
6. **Sequential client, single capture.** One request in flight at a time, as a
   camera client behaves. Concurrency limits (50 Tomcat threads, 5 pool
   connections) were never approached: 10 workers created, 2 connections used.
7. **Boot numbers include native extraction.** `/tmp/opencv_openpnp*` is deleted
   before each measured boot on purpose; a warm cache boots faster.
8. **Rate limiter untested here.** No `429` occurred in the runs reported above
   (`rateLimitedRequests: 0` in all three), so these figures describe the engine's
   own ceiling, not a limiter-shaped workload. A deliberately over-paced
   exploratory run did trip it: 574 frame requests in 30 s produced 394 `429`s
   once the session's 60-requests-per-10 s frame budget was spent.

## 8. What the numbers say

- **Boot is dominated by startup work, not by the artifact size.** 58.3 s median
  against a 120 s budget — half the budget, and the slim (89 MiB) jar is what was
  measured. The first boot on a cold page cache read 71.3 s (+22 %).
- **Per-frame cost is the ONNX path, and it is paid three times per passive frame:**
  54.2 ms face detect + 103.9 ms ONNX score + 95.2 ms PAD ONNX ≈ 253 ms of the
  304.7 ms request. That reproduces the duplicate-detection/inference finding in
  §10 of the interoperability report — the same 160 ms appears at the SPI and
  305 ms over HTTP. **Fixed on 2026-10-13 — see §9** for the re-measurement.
- **A sequential capture costs ~1.7 cores** on this host and yields 2.59 frames/s.
  On a 2-core device that leaves very little headroom for a second session, which
  is the concrete reason NFR-5's "a few frames per second" is the right target.
- **Memory is not the constraint at idle**: 345 MiB resident against a 1.88 GiB
  heap ceiling the process never came near. But 100 frames added 62.9 MiB, and that
  is the figure to watch if long runs matter.
- **Configuration is comfortable:** 10 of 50 Tomcat workers and 2 of 5 pool
  connections under continuous load; both are demand-driven, so neither is a
  tuning problem at this scale.

## 9. Change report — one detection and one inference per frame (2026-10-13)

§4 and §8 measured the finding this section fixes: one passive frame paid
**three** Haar cascades and **two** MiniFASNet inferences inside the ONNX
backend (`analyzeFrame` detected, then each probability pass re-detected and
re-inferred), plus a separate face-gate detection in `ImageUtils`. Three code
changes followed, measured here on the same host and harness.

### 9.1 What changed

1. **Per-frame memo, keyed by pixel content** (`OnnxMiniFasNetBackend`). One
   slot per thread holds the last frame's analysis — face count and box, quality,
   raw `[print, live, replay]` probabilities — keyed by two independent 32-bit
   checksums (CRC32 + CRC32C) plus geometry, and stamped with a model
   *generation* bumped on every load/unload. `analyzeFrame`,
   `scorePassiveLiveness` and `assessPad` share it, so the engine's per-frame
   conversation and the HTTP path's two rebuilt `Frame` objects (fresh object and
   timestamp, identical pixels) share one detection and one inference. It holds
   derived values only — never the pixel buffer — so a slot costs a `Rect`, two
   doubles and three floats per thread. CRC32 costs **1.03 ms** on a 3.5 MiB
   frame (measured; CRC32C 1.39 ms, FNV-1a 17.65 ms and therefore rejected).
2. **Native-buffer hygiene.** `MatOfRect` results (`detectFaces`, `detectEyes`) and
   the placeholder mean `MatOfDouble` were never released on the per-frame paths;
   every buffer is now released in `finally`. The Haar cascades needed no work:
   the only `new CascadeClassifier` sites in `src/main` are lazy/initialization
   paths (`ImageUtils.getFaceCascade/getEyeCascade`, the backend's `initialize`),
   so no frame ever re-parsed the XML.
3. **Configurable ONNX intra-op threads** (`mosip.liveness.onnx-intra-op-threads`,
   backend option `intraOpThreads`). Unset keeps the historical derivation
   `max(1, cores / 2)` — **exactly 1 thread on the 2-core target**. Measured on
   this 4-core host, per distinct frame: **1 thread 49.5 ms**, 2 threads 54.1 ms,
   4 threads 123.3 ms — fewer threads is faster, so the 2-core default is also
   the fastest measured setting. `0` derives the value; a **negative** setting
   stops startup with a message naming the key, a **non-integer** stops the boot
   at Spring's binding (checked against the packaged service:
   `--mosip.liveness.onnx-intra-op-threads=bogus` cancels the context refresh),
   and the backend option itself accepts only a positive integer — so no invalid
   setting silently falls back to ORT's all-cores default.

### 9.2 Before / after

Same host, fixture and conversation order as §4/§5.3. "Before" is this
document's own measurement: the pre-change code did the same work for a repeated
frame as for a new one (nothing was cached between calls), so those means are
valid for both workloads.

| Measurement | Before | After | Δ |
| --- | --- | --- | --- |
| SPI conversation, **distinct frames** — mean | 160.899 ms | **61.769 ms** (p50 56.830 · p95 100.448 · 16.2 fps) | **−61.6 %** |
| Same test, second isolated run | — | 61.003 ms mean · 16.4 fps | consistent |
| Same test, inside the full suite | — | 74.799 ms mean | load-dependent, see §9.4 |
| SPI conversation, **repeated frame** | 160.899 ms | **0.242 ms** mean · ≈4 135 fps | memo hits |
| HTTP passive frame, **distinct payloads** — pipeline total | 304.71 ms | **167.75 ms** (p99 314.57) | **−45.0 %** |
| HTTP passive frame, **identical payload** — pipeline total | 304.71 ms | **79.43 ms** (p99 299.89) | −73.9 % |
| HTTP client mean, distinct payloads | 0.338 s (identical payload) | **0.200 s** (p50 0.169 · p95 0.278) | −40.8 % |
| HTTP client mean, identical payload | 0.338 s | **0.097 s** (p50 0.085 · p95 0.219) | −71.3 % |
| Detections per frame (SPI conversation) | 3 | **1** | −66.7 % |
| Inferences per frame (SPI conversation) | 2 | **1** | −50 % |
| Slim jar | 93 761 602 B | 93 765 658 B (+4 056 B) | +0.004 % |

Server-side phases over the 110 distinct HTTP payloads (§5.3 method):

| Phase | Before | After | Note |
| --- | --- | --- | --- |
| `decode` | 8.78 ms | 7.90 ms | unchanged work |
| `facedetect` (HTTP face gate, `ImageUtils`) | 54.15 ms | 56.21 ms | one detection, still paid once per frame |
| `onnxscore` | 103.94 ms | **0.58 ms** | `analyzeFrame` + `scorePassiveLiveness` are memo hits plus hashing |
| `padheuristic` | 3.35 ms | 3.51 ms | unchanged work |
| `paddonx` | 95.24 ms | **60.19 ms** | carries the frame's one detection (≈48 ms) + one inference (≈12 ms) |
| `total` | 304.71 ms | **167.75 ms** | |
| outside the pipeline (DB, audit, limiter) | ≈39 ms | ≈40 ms | untouched code |

### 9.3 Evidence that no value moved

- `OnnxMiniFasNetBackendTest.memoizedAnalysisReproducesThePreMemoValues`
  compares values captured from the pre-change code by a probe that was then
  deleted, with a 1 ppm tolerance: ONNX Runtime selects float32 kernels per CPU
  generation, so the same fixture lands a few ulps apart on a different runner
  and an exact comparison would fail without a value having moved. Two calls
  served from the memo are then compared with `Double.toHexString` — every
  mantissa bit — so a memo that recomputes instead of reusing still fails.
  Genuine face `real-face.jpg`: faceCount 1 · quality
  `0x1.db82b901dc375p-1` · score `0x1.fd6f64eda63ap-1` · PAD bona fide
  `0x1.fd6f64eda63ap-1`. Print attack `print-attack.jpg`: faceCount 1 · quality
  `0x1.c1dd992d3df9ep-2` · score `0x1.1fbc56520293cp-11` · PAD `SCREEN_REPLAY`
  `0x1.ffb70c86ca4cap-1`.
- `intraOpThreadCountDoesNotMoveAnyValue`: 1 thread, the derived default and 4
  threads answer identically on both fixtures.
- `oneFrameThroughTheSpiConversationCostsOneDetectionAndOneInference` and
  `memoKeysOnPixelContentNotOnTheFrameObject`: the counts above, that
  `analyzeFrame` alone pays no inference, that a reload invalidates the memo, and
  that a rebuilt frame reuses the analysis while different pixels do not.
- End-to-end: both HTTP workloads answered `200` for every request and all 42
  sessions passed (22 + 20); the identical payload returns
  `livenessScore 0.9139281719741627` — the exact value §5.3 recorded before the
  change. `rateLimitedRequests` was 0 in both runs.
- Untouched by the change: thresholds, median window, PAD confirm frames,
  challenge logic, policy handling, rate limiting, audit chain, headers and key
  checks — none of those files appears in §9.5.

### 9.4 How these numbers were taken, and what they do not say

- **A benchmark that reuses one frame no longer measures a per-frame cost.**
  The memo makes repeated pixels nearly free, so `BackendPerFrameLatencyTest` now
  measures two workloads and prints both: `repeated-frame` (the §4 harness: one
  frame object, 100 iterations) and `distinct-frames` (100 frames perturbed by a
  per-channel offset of 0–4 counts, so no two share a memo entry — the camera
  case). **Quote per-frame cost from the distinct line.** The pre-change harness
  only had the repeated workload; that is not a like-for-like comparison of the
  fix, which is why §9.2 also reports detection/inference counts and the HTTP
  phase split, neither of which depends on the workload.
- The distinct-frame perturbation is deliberately mild (0–4 counts per channel,
  plus 0–4 % brightness/quality/modulation on the HTTP payloads): every frame
  still presents exactly one detectable face, so the ONNX path really runs
  instead of short-circuiting to the heuristic. Checked in both directions — the
  SPI run's score range was `[0.9899, 0.9984]`, the HTTP run's diagnostics
  delegate was `onnx-minifasnet-v2`, and the phases show nonzero detection and
  inference work.
- The HTTP runs set `SESSION_CREATE_LIMIT=300` for the harness only (22 + 20
  sessions would otherwise approach the 30/min per-IP budget). The limiter itself
  is untouched and recorded 0 rejections; client means include the local `curl`
  process, as in §5.2.
- This host has 4 cores, not the 2-core target. The intra-op measurements say a
  2-core machine pays the 1-thread figure; the clock governor also scaled the
  package during the runs, so the timings are pessimistic.
- **Remaining duplication that is deliberately not removed:** the HTTP gate's
  `facedetect` (56.21 ms) and the backend's own detection inside `paddonx`
  (≈48 ms) both scan the same pixels. Sharing one box across the SPI boundary
  would change the backend contract for a face box the gate may have computed
  under different parameters — a contract change, not a dedup, and out of scope
  for a change whose rule was "no returned value moves".

### 9.5 Files changed for this report

| File | Change |
| --- | --- |
| `src/main/java/io/mosip/liveness/backend/OnnxMiniFasNetBackend.java` | per-frame content-keyed memo, generation invalidation, cascade/Mat release, input-name reuse, `intraOpThreads` option, detection/inference counters |
| `src/main/java/io/mosip/liveness/services/ImageUtils.java` | release `MatOfRect`/`MatOfDouble` buffers, exception-safe `finally` release, decode buffer release |
| `src/main/java/io/mosip/liveness/services/LivenessEngineService.java` | release face/mouth ROI, gray, Laplacian and `MatOfDouble` buffers in the quality and challenge paths |
| `src/main/java/io/mosip/liveness/services/PadEngineService.java` | release the six per-frame native buffers on the failure path too |
| `src/main/java/io/mosip/liveness/services/PassiveScoringService.java` | pass the configured intra-op thread count to the backend; refuse a negative value at construction |
| `src/main/resources/application.yml` | document `mosip.liveness.onnx-intra-op-threads` (default 0 = derive) |
| `src/test/java/io/mosip/liveness/backend/OnnxMiniFasNetBackendTest.java` | bit-identity, memo-key, thread-count and option-validation tests |
| `src/test/java/io/mosip/liveness/backend/BackendPerFrameLatencyTest.java` | two workloads (repeated / distinct) with `workload=` in the results line |
| `src/test/java/io/mosip/liveness/services/PassiveScoringServiceTest.java` | the negative-thread-count configuration refusal |
| `docs/configuration.md` | the new key in the `mosip.liveness` option table |
| `CHANGELOG.md`, `docs/backend-interoperability-report.md` | entry for the change; the §10 note that the triple-detection finding is fixed and which workload to quote |

**On which jar the numbers above were taken:** the SPI and HTTP runs used the
slim jar built after changes 1–3. The later constructor guard for a *negative*
thread count was added afterwards and was verified on the packaged jar by booting
it: `--mosip.liveness.onnx-intra-op-threads=-2` refuses the boot with a message
naming the key, while `=1` starts with scorer `onnx-minifasnet-v2` and the same
`0.9139281719741627`. The guard adds no work to any per-frame path, so it does
not change the measured per-frame cost.

## 10. Change report — capture and analysis budget (2026-10-13)

§4/§5 measured what a frame *costs*; this section measures what one **costs to
send**. The console captured the camera's native frame — 1280x720 on a webcam that
ignores the `640x480` `ideal` it asks for — and encoded it at quality 0.85 (0.9
for challenge frames). That is 921 600 pixels drawn, JPEG-encoded, base64'd,
uploaded, base64-decoded and resized by the service **per frame, four times a
second, for a face the service caps at 1280 px anyway**. The change is a capture
budget on the client plus a configurable analysis cap on the server; the decision
path is untouched, and the numbers below say exactly what moved and what did not.

### 10.1 What changed

1. **A 640 px capture at quality 0.7** (`static/console.js`). `captureTarget()`
   scales the source frame's long edge to at most `CAPTURE_MAX_EDGE = 640`
   (never upscaling a 320x240 camera) and `grabFrame()` draws the **whole** source
   rectangle into that destination — the 9-argument `drawImage` form, because the
   5-argument form copies the frame's top-left corner and would have *cropped* the
   face rather than downscaled it. `CAPTURE_JPEG_QUALITY = 0.7` replaces the
   per-call 0.85/0.9 defaults, one canvas is reused for every frame, and the
   ceilings are published read-only as `window.CAPTURE_BUDGET` so
   `console-guards.test.mjs` can pin them instead of scraping the source.
2. **One gate for both capture screens.** `capturePaused()` (tab hidden, or a
   request in flight) and `awaitCaptureSlot()` (`CAPTURE_MAX_FPS = 8`, i.e. 125 ms
   between sends) wrap every frame and challenge POST via `sendFrame()`. Four call
   sites pass through it — the Supervisor passive loop, the shared challenge loop,
   the Resident passive loop and the Resident challenge loop — so two open screens
   sharing one camera cannot combine into 16 sends/s. A capture taken while a
   request is in flight is **dropped** (the caller retries on its next tick) and a
   skipped tick no longer spends one of a run's twelve attempts (`i--`), so a
   pause costs throughput instead of attempts.
3. **Capture pauses on a hidden tab**, with a line in the decision log. The camera
   stream stays open on purpose: stopping a `MediaStreamTrack` makes the camera
   re-acquire with a visible flash, and what the gate protects is the canvas encode
   plus the upload, not the already-running video element.
4. **`mosip.liveness.analysis-max-dimension`** (default 1280, the value that was
   hard-coded) makes `ImageUtils`'s long-edge cap configurable. It is applied in
   `decodeFrame()` after the fixed 6 MB payload, 8192 px dimension and 40 M-pixel
   caps and before the caller runs face detection, quality scoring and PAD; values
   below 64 refuse the boot (`analysis-max-dimension must be at least 64, was 32`)
   instead of being clamped, because the cascade's smallest model input is 60 px.
5. **The challenge burst is unchanged on purpose**: baseline + up to the 4-frame
   window, 4 frames on the first poll. The engine measures the *largest excursion
   relative to the baseline*, so trimming the window would change what it
   evaluates. What changed is the price of each frame in it and the shared ceiling.

### 10.2 Before / after

**Client payload and encode** — measured in Blink (headless Chromium, the same
`drawImage` + `toDataURL('image/jpeg', q)` calls the console makes) on one
1280x720 frame built from `real-face.jpg`. "Before" is what the console used to
send for a webcam that ignores the requested `640x480` `ideal`:

| Capture setting | Payload bytes (request body) | draw + encode, median (min) |
| --- | --- | --- |
| 1280x720 @ q0.85 — before, passive | 42 853 | 79.6 ms (48.4) |
| 1280x720 @ q0.90 — before, challenge | 50 545 | 42.8 ms (35.1) |
| **640x360 @ q0.70 — after** | **12 033** | **19.4 ms (12.6)** |
| 640x480 @ q0.85 — before, camera that honours the ideal | 18 313 | 20.2 ms |
| 640x480 @ q0.70 — after, same camera | 13 889 | 22.1 ms |

| Measurement | Before | After | Δ |
| --- | --- | --- | --- |
| Passive frame payload | 42 853 B | **12 033 B** | **−71.9 %** |
| Challenge frame payload (q0.90) | 50 545 B | **12 033 B** | **−76.2 %** |
| Client draw + encode per frame | 79.6 ms | **19.4 ms** | **−75.6 %** |
| Payload, 640x480 camera (pixels unchanged, quality only) | 18 313 B | 13 889 B | −24.2 % |
| Pixels drawn, encoded and uploaded per frame | 921 600 | 230 400 | −75 % |

**Server per-frame cost**, from the service's own phase timers
(`/api/v1/metrics` → `pipelineTimings`, count/`totalMs` deltas over 8 frames per
variant on one JVM warmed on both sizes first). Both rows are the same source
pixels at the two capture settings; session creation was done before the measured
window and paced under the service's own 30/min limit (every response was 200):

| Fixture | Phase | Before (1280x720 q0.85) | After (640x360 q0.70) | Δ |
| --- | --- | --- | --- | --- |
| real-face | `decode` | 34.5 ms | **7.9 ms** | −77.1 % |
| real-face | `facedetect` | 152.8 ms | **61.9 ms** | −59.5 % |
| real-face | `onnxscore` | 10.5 ms | **5.5 ms** | −47.6 % |
| real-face | `padheuristic` | 32.3 ms | **11.8 ms** | −63.5 % |
| real-face | `paddonx` | 162.8 ms | **48.2 ms** | −70.4 % |
| real-face | **`total`** | **428.7 ms** | **162.3 ms** | **−62.1 %** |
| real-face | client wall, median (8 samples) | 439.9 ms | 177.2 ms | −59.7 % |
| print-attack | `decode` | 25.2 ms | **11.9 ms** | −52.8 % |
| print-attack | `facedetect` | 128.6 ms | **66.6 ms** | −48.2 % |
| print-attack | `padheuristic` | 30.7 ms | **10.1 ms** | −67.4 % |
| print-attack | `paddonx` | *(heuristic short-circuited the model pass)* | 60.2 ms | path change, see 10.3 |
| print-attack | **`total`** | **212.7 ms** | **172.6 ms** | −18.8 % |
| print-attack | client wall, median (8 samples) | 231.6 ms | 193.5 ms | −16.4 % |

Every pixel-proportional phase drops by half to three quarters (decode −77 %,
face detection −60 %, PAD model −70 % on the genuine fixture). The genuine
fixture's total falls −62 %. The print fixture's total falls only −19 %, because
on that frame the *before* path short-circuited: the heuristic PAD flagged the
attack and `detectPad()` returned before running the model pass, so the smaller
frame "adds" a 60 ms inference the 720p frame never paid — see 10.3.

**The analysis cap, same 1280x720 payload** (warmed, 8 frames each; the default
row is the same measurement as the real-face row above):

| `analysis-max-dimension` | `decode` | `facedetect` | `total` | `faceDetected` | `livenessScore` |
| --- | --- | --- | --- | --- | --- |
| 1280 (default) | 34.5 ms | 152.8 ms | 428.7 ms | true | 0.9495 |
| 640 | 34.4 ms | 50.2 ms | 151.4 ms | true | **0.6444** (below the 0.80 threshold → escalate) |
| 480 | 38.1 ms | 26.0 ms | 93.8 ms | **false** | — (no face to score) |

Three things that table says: the cap is applied **before** detection and scoring
(the verdict moves with the cap, not after it); it cannot avoid the JPEG decode
(34.4 ms at cap 640 against 34.5 ms at cap 1280 — the pixels are needed before any
resize); and it is a cost bound rather than a free knob, which is why the default
stays at 1280 and the console captures below it anyway.

Note that the cap rows hold the *payload* fixed at 1280x720 q0.85, so cap 640
decodes the full 720p JPEG and scales it afterwards. The console's own frame has a
different pixel history — 640x360 scaled *before* the single q0.70 encode — and the
same nominal 640 px long edge therefore reads `0.6444` here and `0.8555` in 10.3.
Both are one frame of one fixture rather than a population claim, and the 10.3 row
is the one that describes the shipped console path.

### 10.3 Evidence that no decision moved, and what did

Same fixtures, same requests, decision fields compared before/after
(`POST /api/v1/sessions/{id}/frames`, one fresh session per frame):

| Fixture | Field | Before (720p q0.85) | After (640x360 q0.70) |
| --- | --- | --- | --- |
| real-face | `faceDetected` | true | true |
| real-face | `padFlag` | false | false |
| real-face | `action` | retry_passive | retry_passive |
| real-face | `faceQuality` | 0.2787 | 0.4288 |
| real-face | `livenessScore` | 0.9495 | 0.8555 (still above the 0.80 threshold) |
| print-attack | `faceDetected` | true | true |
| print-attack | `padFlag` | **true** | **true** |
| print-attack | `action` | retry_passive | retry_passive |
| print-attack | `padAttackType` | PRINTED_PHOTO (heuristic) | SCREEN_REPLAY (model) |

- **Every decision field is identical**: the face gate, the PAD block and the
  sweep's action are the same on both fixtures. Face detection still works on a
  640 px frame — that was the check, and both fixtures report `faceDetected=true`
  at 640x360.
- **The scores behind them are not, and that is the trade-off.** The genuine
  fixture's passive score reads 0.9495 → 0.8555: still above the 0.80 threshold,
  but with less margin. (The sweep table above reads a lower `0.6444` at the same
  nominal 640 px, because that row scales a q0.85 720p payload after decode instead
  of encoding already-scaled pixels — see the note under 10.2.)
  The print fixture's *label* moves from the heuristic's `PRINTED_PHOTO` to the
  model's `SCREEN_REPLAY`, because at 640 px the heuristic
  no longer short-circuits the model pass — visible in the phase table as a
  `paddonx` count that is 0 before and 8 after. `padFlag` stays true either way,
  so the block is unchanged; the APCER-by-type tallies and the audit's
  `padAttackType` will report the model's label for frames this small. Fewer
  pixels and fewer JPEG bits reach the model — that is what the 72 % payload
  reduction buys, and it is why the capture settings are documented constants and
  not silent numbers.
- **Nothing about the decision path changed.** Thresholds, median window, PAD
  confirm frames, challenge logic, policy handling, rate limiting, audit chain,
  headers and key checks are untouched; no file in 10.5 is one of those.
- **No test result moved.** `./mvnw --batch-mode test` — 697 tests, 0 failures,
  0 errors (the Java suites feed fixture-native 256x320 frames, below both the old
  and the new capture setting, so they cannot see this change); the console guards
  harness — 96 assertions, 0 failures; `node scripts/check-project-boundaries.mjs`
  — exit 0.
- **The client-side guards are pinned by tests, not by inspection**
  (`console-guards.test.mjs`, 23 new assertions of the file's 96): a 1280x720 camera is captured at
  640x360 with the whole 1280x720 source rectangle drawn (scale, not crop) and the
  aspect ratio intact; the encode is JPEG at 0.7; a 320x240 camera keeps its own
  size (the cap never upscales); one canvas is allocated per page; no frame is
  captured or sent while the tab is hidden and the log says why; a capture taken
  while a request is in flight is dropped, not queued, and exactly that one frame
  goes out once the pipe is free; the challenge burst stays baseline + up to 4
  frames; and every gated send in the whole run is at least 125 ms after the
  previous one.

### 10.4 How these numbers were taken, and what they do not say

- **This host was loaded.** Its load average ran 6.5–7.9 (a shared desktop with an
  indexer and a browser on it), so every absolute millisecond here is pessimistic
  and, more importantly, noisy: both capture settings were measured back-to-back on
  the *same* warmed JVM to keep the comparison fair, and the phase timers — not
  process CPU — carry the claim. Process CPU per frame is deliberately not quoted:
  on this host it is dominated by per-request bookkeeping (H2, audit, JPA flush)
  and by JIT, which is why the first, unwarmed pass charged one variant 2 198 ms of
  CPU per frame and the next 361 ms.
- **Two encoders appear above.** The byte and encode-time numbers come from Blink's
  own JPEG encoder (the one the console actually uses); the server-side timing and
  verdict numbers come from ImageMagick-encoded frames of the same source pixels at
  the same two settings, so the before/after comparison is like-for-like. Blink's
  encoder is the more efficient of the two at q0.7 (12 033 B against ImageMagick's
  20 894 B for the same 640x360 frame), so the payload saving is *understated* by
  the server-side run, never overstated.
- **The CPU saving is a pixel-count claim, not a browser measurement.** 640x360 is
  25 % of 720p's pixels, so the draw and the encode shrink by that factor; the
  measured 79.6 → 19.4 ms is consistent with it but was taken in a headless
  Chromium on this host, not on a 2-core device. What is not a claim: the payload
  bytes, which are exact.
- **The 8/s ceiling is a ceiling on the shared gate, not a measured throughput.**
  The console captures every 500 ms (`CAPTURE_MS`) in practice, so the ceiling only
  binds when two screens or a slow upload push sends together. The guards harness
  proves the sends are ≥125 ms apart; it cannot prove what a real camera and a
  slow network would do.
- **Challenge-loop savings are bounded by the window, not by the cadence.** A poll
  still carries baseline + the recent window (4 frames here), because the engine's
  comparison needs the baseline; the saving is 4 x (50 545 − 12 033) B ≈ 154 KB per
  poll, not fewer polls.

### 10.5 Files changed for this report

| File | Change |
| --- | --- |
| `src/main/resources/static/console.js` | capture budget constants + `window.CAPTURE_BUDGET`, whole-source `drawImage` downscale, quality 0.7, shared `capturePaused()`/`awaitCaptureSlot()` gate around every frame and challenge POST, drop-instead-of-queue, hidden-tab pause with a log line, skipped ticks no longer consuming attempts |
| `src/main/java/io/mosip/liveness/services/ImageUtils.java` | `analysisMaxDimension` as a constructor `@Value` option (default 1280, minimum 64 refused at startup), cap applied after the fixed caps and before any analysis work |
| `src/main/resources/application.yml` | `mosip.liveness.analysis-max-dimension: 1280` with the cost-bound rationale |
| `src/test/js/console-guards.test.mjs` | canvas/encode recording, document visibility and create-element stubs, a `navigator.mediaDevices` stub to drive the resident screen, controllable in-flight frame POST, and 23 new assertions (budget, no-upscale, hidden pause/resume, drop-not-queue, burst composition, send pacing) |
| `src/test/java/io/mosip/liveness/services/ImageUtilsCascadeTest.java` | the configured cap is honoured, the default is 1280, and a below-minimum cap is refused |
| `docs/configuration.md`, `CHANGELOG.md` | the new key, and the entry for the capture budget |
| `docs/performance-baseline.md` | this section |

**On which artifact the numbers above were taken:** the slim jar built after
these changes, booted with `--spring.profiles.active=dev`; the cap rows boot it
with `--mosip.liveness.analysis-max-dimension=640` and `=480`, and the refusal
with `=32`.

---

## 11. Change report — console delivery and process startup (2026-10-16)

The report behind this section was "the console takes a long time to load".
Measured, the page was *not* the slow part: on this host a warm navigation
reached `DOMContentLoaded` in 0.26–0.66 s, while a `dev` start took **20.9 s** to
answer `/health`. One real delivery defect was found (the service's 113 KB script
crossed the wire uncompressed, on every load, for every user) and fixed; three
startup knobs were measured and **rejected** because they paid nothing; and a
search for dead console code found none to delete.

### 11.1 What changed

1. **`text/javascript` was missing from `server.compression.mime-types`.** That
   list *replaces* Spring Boot's default set, and the default is where
   `text/javascript` came from — and `text/javascript` is the type Spring serves
   a `.js` resource as (RFC 9239). The console's script was therefore the one
   asset that was never compressed. The list now carries both spellings, so the
   script is compressed whichever the framework or a reverse proxy reports it as.
2. **The console's three files carry `Cache-Control` now**
   (`spring.web.resources.cache.cachecontrol`: `max-age=600, must-revalidate,
   public`). They carried none before, so every reload re-fetched or revalidated
   ~190 KB of markup, CSS and script that cannot have changed. `must-revalidate`
   keeps `Last-Modified`, so the cost after the window is one conditional `304`
   per file.
3. **Nothing else.** No minifier, no bundler, no build step, no new dependency:
   gzip removes the bytes and the comments carry the reasoning (§11.5).

### 11.2 Before / after

Wire bytes for one *cold* load of the console (no cache), measured with `curl
-H 'Accept-Encoding: gzip'` against the running service:

| Asset | Before | After | Change |
| --- | --- | --- | --- |
| `/console.js` | 115 565 | **32 766** | −71.6 % (it was not compressed at all) |
| `/console.css` | 9 992 | 9 992 | unchanged (already compressed) |
| `/` (`index.html`) | 8 295 | 8 295 | unchanged (already compressed) |
| **Total** | **133 852** | **51 053** | **−61.9 %** |

For reference, the same three files uncompressed are 197 613 bytes.

Repeat load, from Blink's resource timing on a fresh navigation to `/`
(loopback, warm): `console.css` and `console.js` both report `transferSize: 0` —
served from the memory cache with **no request at all**, which is the two round
trips this removes from every reload. The page itself: `responseStart` 18 ms,
`DOMContentLoaded` 264 ms, `load` 265 ms.

### 11.3 Measured and rejected — three knobs that did not pay

Each row is this host, `dev` profile, one run per configuration, stopped at
`/health`.

| Candidate | Total to `/health` | Spring's own "Started in" | Verdict |
| --- | --- | --- | --- |
| baseline (`./mvnw spring-boot:run`) | 20 860 ms | 10.07 s | — |
| `spring.main.lazy-initialization=true` | 21 079 ms | 9.77 s | **rejected**: inside run-to-run noise, and it moves a wiring failure from boot to the first request |
| `-XX:TieredStopAtLevel=1 -Xshare:auto` | 21 572 ms | 10.06 s | **rejected**: no change — the cost is class loading and Hibernate, not JIT |
| `java -jar` (built fat jar) | 30 159 ms | 26.05 s | **rejected**: *worse* — nested-jar class loading over a 244 MB file |

### 11.4 What the ~21 s start is actually made of

| Phase | Time |
| --- | --- |
| Maven bootstrap on its own (`./mvnw -o -q validate`) | 5.6 s |
| `compile` + `resources` (exploded classes) | ~5 s |
| Spring: banner → Tomcat initialized (component scan over 139 jars) | 2.8 s |
| Spring: Tomcat initialized → started — Hibernate metamodel + schema 3.4 s, OpenCV native load 0.23 s, remaining bean init ~2.8 s | 7.2 s |

The wait is JVM class loading, Maven and Hibernate. Cutting it would need one of
those three, and the three candidates in §11.3 do not. A `dev` start is therefore
unchanged by this report, and the useful operational note is that static-file
edits do not need a restart at all: `./mvnw resources:resources` copies them into
`target/classes`, which the running service already serves.

### 11.5 What was searched for and not found

"Remove unnecessary stuff" was checked rather than assumed, on the console's own
three files:

- **`console.js` — 79 functions, every one referenced.** No dead function, no
  unreachable branch, nothing defined for a caller that does not exist.
- **`console.css` — 185 class selectors, 172 referenced literally.** The other 13
  look dead to a grep because they are composed at runtime
  (`className = "pill " + kind`, `"verdict " + kind`); deleting them would break a
  visual state, not remove weight.
- **`index.html` — 174 ids, 27 of them invisible to a grep** for the same reason
  (`$("tab-" + t)`, `$("s" + n)`, `$("panel-" + …)`).

So there was nothing dead to delete. The files are large because they are a whole
console plus the comments that explain its guards, and gzip is what takes those
comments off the wire without deleting the reasoning from the source.

### 11.6 How these numbers were taken, and what they do not say

- **Host load:** load average 3.3 / 4.5 / 5.0 during the run on the §2 machine, so
  the startup milliseconds are pessimistic and noisy. The transfer bytes are
  exact and repeatable; the startup deltas are quoted only to reject a knob
  (all four configurations land within 0.7 s of each other, except the jar row).
- **One run per configuration.** The rejection of lazy initialisation and of the
  tiered-compilation flags rests on a *single* sample each that landed inside the
  same spread as the baseline. A knob worth 2 s here would have shown.
- **These are warm-cache starts, not §5.1's cold-cache CI parity number** (58.3 s
  median on this same host with the OpenCV native cache cleared before every
  boot). Do not compare the two tables.
- **The cache window is a trade, not a free win:** a browser that has already
  loaded the console can be up to 10 minutes behind a restarted service's static
  files. Ten minutes was chosen as the point where a reload stops re-fetching
  160 KB and a console can still not outlive an update by more than one coffee.
  **That window has since been removed (2026-10-24).** It cost more than it paid:
  a cached console kept showing the pre-update sign-in dialog after the service
  had been rebuilt, so the operator re-entered credentials into a form the jar no
  longer had and reported sign-in as broken. The three files now carry
  `max-age=0, no-cache, must-revalidate` (sent as `no-cache, must-revalidate,
  public`), which makes each load fetch the bytes the running process serves —
  51 KB compressed, per the table above.
- **The page timings are loopback in Chromium on this host.** They say nothing
  about a 2-core registration station on a LAN beyond the transfer bytes, which
  are exact, and they do not isolate how much of the remaining ~250 ms is script
  parse versus layout.

### 11.7 Files changed for this report

| File | Change |
| --- | --- |
| `src/main/resources/application.yml` | `text/javascript` added to `server.compression.mime-types`; `spring.web.resources.cache.cachecontrol` (`max-age: 10m`, `public`, `must-revalidate`) |
| `docs/performance-baseline.md` | this section |
| `CHANGELOG.md` | the entry for this change |

**On which artifact the numbers above were taken:** `./mvnw spring-boot:run` with
`SPRING_PROFILES_ACTIVE=dev` on the §2 host, warm Maven repository, warm OpenCV
native cache and warm page cache — the configuration this report measured is the
configuration it changed.
