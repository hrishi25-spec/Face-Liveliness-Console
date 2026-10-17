#!/usr/bin/env bash
#
# Boot the packaged jar N times, each from a cold OpenCV native cache,
# and report the median time-to-/health.
#
# Boot time is the one thing none of the other checks measure. The
# smoke test proves the jar *can* boot; nothing notices when it starts
# taking twice as long. A new eager bean, an extra Flyway migration,
# a dependency that scans the classpath at startup — all of those ship
# green and surface only as "deployments are slow now".
#
# "Cold" is the point. The OpenCV natives are extracted once per
# machine into /tmp/opencv_openpnp*, so on a shared runner boot 2..N
# would reuse the cache and quietly measure the *warm* case. The cache
# is cleared before every boot here, so each run pays the extraction
# cost and the median is a median of cold boots — the case a fresh
# deployment (or a fresh GitHub Actions runner) actually hits.
#
# The budget is deliberately generous (~3x today's ~37 s local
# measurement): this gate exists to catch a regression that doubles
# boot, not to shave seconds. Tighten BOOT_MEDIAN_MAX_SECONDS only
# after a deliberate optimization lands.
#
# Exits non-zero when a boot fails to come up at all, or when the
# median drifts past the budget.
set -uo pipefail

# Namespaced on purpose (see smoke-jar.sh): ambient PORT=0 in some
# environments makes Spring bind an ephemeral port and the poll loop
# would burn the whole budget failing with a misleading error.
PORT_BASE="${BOOT_MEDIAN_PORT:-8081}"
RUNS="${BOOT_MEDIAN_RUNS:-5}"
WHICH_JAR="${BOOT_MEDIAN_JAR:-slim}"
MAX_SECONDS="${BOOT_MEDIAN_MAX_SECONDS:-120}"
BOOT_TIMEOUT="${BOOT_MEDIAN_BOOT_TIMEOUT:-240}"
LOG_DIR="${BOOT_MEDIAN_LOG_DIR:-/tmp/boot-median}"
PROFILE="${BOOT_MEDIAN_PROFILE:-dev}"

usage_check() {
  if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]]; then
    echo "::error::BOOT_MEDIAN_RUNS must be a positive integer, got '$RUNS'" >&2
    exit 2
  fi
  for var in MAX_SECONDS BOOT_TIMEOUT PORT_BASE; do
    if ! [[ "${!var}" =~ ^[0-9]+$ ]]; then
      echo "::error::$var must be a whole number, got '${!var}'" >&2
      exit 2
    fi
  done
  case "$WHICH_JAR" in
    full|slim) ;;
    *)
      echo "::error::BOOT_MEDIAN_JAR must be 'full' or 'slim', got '$WHICH_JAR'" >&2
      exit 2
      ;;
  esac
}
usage_check

# Same selection logic as smoke-jar.sh: nullglob turns "nothing matched"
# into an empty array, and the full profile excludes the -slim
# classifier so a target/ holding both jars still boots the right one.
shopt -s nullglob
ALL_JARS=( target/pad-liveness-backend-*.jar )
shopt -u nullglob

if [ "$WHICH_JAR" = "slim" ]; then
  shopt -s nullglob
  JARS=( target/pad-liveness-backend-*-slim.jar )
  shopt -u nullglob
else
  JARS=()
  for jar in "${ALL_JARS[@]}"; do
    case "$jar" in *-slim.jar) ;; *) JARS+=( "$jar" ) ;; esac
  done
fi

if [ "${#JARS[@]}" -eq 0 ]; then
  echo "::error::no jar matching the '$WHICH_JAR' profile in target/ — build it first"
  echo "         full: ./mvnw clean package -DskipTests"
  echo "         slim: ./mvnw clean package -DskipTests -Pslim"
  exit 1
fi
JAR="${JARS[0]}"
echo "measuring boot time (${WHICH_JAR}): $JAR"
echo "plan: $RUNS cold boots, budget ${MAX_SECONDS}s median, logs in $LOG_DIR"

APP_PID=""
# The trap is set once; APP_PID holds the newest JVM, so a mid-boot
# exit never leaks a process even though the variable changes per run.
trap 'kill "${APP_PID:-}" 2>/dev/null || true' EXIT

mkdir -p "$LOG_DIR"
SAMPLES=()
SPRING_TIMES=()

for ((i = 1; i <= RUNS; i++)); do
  PORT=$(( PORT_BASE + i - 1 ))
  LOG="$LOG_DIR/boot-$i.log"

  # Cold: drop the extracted natives so this boot pays extraction again.
  rm -rf /tmp/opencv_openpnp*

  START_MS=$(date +%s%3N)
  java -jar "$JAR" --spring.profiles.active="$PROFILE" --server.port="$PORT" \
    > "$LOG" 2>&1 &
  APP_PID=$!

  # Poll every second — a timing measurement wants finer granularity
  # than the smoke test's 3 s, and boot is tens of seconds, not minutes.
  BODY=""
  for _ in $(seq 1 "$BOOT_TIMEOUT"); do
    if BODY=$(curl -sf -m 5 "http://127.0.0.1:$PORT/health" 2>/dev/null); then
      break
    fi
    # A jar that dies on startup is the common failure; fail on it
    # immediately instead of burning the per-boot budget first.
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "::error::boot $i/$RUNS: packaged jar exited before /health responded"
      tail -40 "$LOG"
      exit 1
    fi
    sleep 1
  done
  END_MS=$(date +%s%3N)

  if [ -z "$BODY" ]; then
    echo "::error::boot $i/$RUNS: packaged jar did not serve /health within ${BOOT_TIMEOUT}s"
    tail -40 "$LOG"
    exit 1
  fi
  case "$BODY" in
    *'"engine":"available"'*) ;;
    *)
      # A degraded boot would be *faster* and drag the median down,
      # hiding a regression — a boot without its engine is a failure,
      # not a sample.
      echo "::error::boot $i/$RUNS: OpenCV natives missing or unloadable in the packaged jar"
      tail -40 "$LOG"
      exit 1
      ;;
  esac

  MS=$(( END_MS - START_MS ))
  SAMPLES+=( "$MS" )
  # Spring's own number (JVM-internal view) is kept alongside the
  # wall clock so a slow boot can be attributed: wall >> spring means
  # class loading / JVM startup, wall ~= spring means the app itself.
  SPRING=$(sed -n 's/.*Started PadLivenessApplication in \([0-9.]*\) seconds.*/\1/p' "$LOG" | tail -1)
  SPRING_TIMES+=( "${SPRING:-?}" )
  printf 'boot %d/%d: %d.%02d s to /health (spring reports %s s)\n' \
    "$i" "$RUNS" "$(( MS / 1000 ))" "$(( (MS % 1000) / 10 ))" "${SPRING:-?}"

  # Wait for the JVM to exit so the port is free before the next boot.
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
done

# Median of the samples (integer ms; even counts average the middle two).
MEDIAN_MS=$(printf '%s\n' "${SAMPLES[@]}" | sort -n | awk -v n="${#SAMPLES[@]}" '
  { a[NR] = $1 }
  END {
    if (n % 2 == 1) print a[(n + 1) / 2]
    else printf "%.0f", (a[n / 2] + a[n / 2 + 1]) / 2
  }')
MEDIAN_S=$(awk -v ms="$MEDIAN_MS" 'BEGIN { printf "%.1f", ms / 1000 }')

echo "median cold boot: ${MEDIAN_S} s over $RUNS runs (budget ${MAX_SECONDS} s)"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Cold boot time — median ${MEDIAN_S} s"
    echo
    echo "| boot | wall-clock to /health | spring-reported |"
    echo "| --- | --- | --- |"
    for ((i = 0; i < ${#SAMPLES[@]}; i++)); do
      printf '| %d | %.1f s | %s s |\n' \
        "$(( i + 1 ))" "$(awk -v ms="${SAMPLES[$i]}" 'BEGIN { printf "%.1f", ms / 1000 }')" \
        "${SPRING_TIMES[$i]}"
    done
    echo
    echo "Jar: \`${WHICH_JAR}\` · ${RUNS} boots, each from a cleared OpenCV native cache · budget ${MAX_SECONDS} s median"
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ "$MEDIAN_MS" -gt "$(( MAX_SECONDS * 1000 ))" ]; then
  echo "::error::median cold boot ${MEDIAN_S}s exceeds the ${MAX_SECONDS}s budget"
  echo "         a new eager init, migration or classpath scan is the usual cause"
  exit 1
fi

echo "boot time within budget"
