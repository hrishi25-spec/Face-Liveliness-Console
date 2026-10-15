#!/usr/bin/env bash
#
# Boot the packaged jar and prove it actually serves traffic.
#
# The test suites run against target/classes with the full ~/.m2 classpath, so
# nothing they do can catch a *packaging* regression: a fat jar that lost its
# OpenCV natives, a wrong Start-Class (spring-boot writes JarLauncher itself
# when a <classifier> is set, and the loader then re-launches until
# StackOverflowError), a truncated repackage. All of those ship green and only
# break for whoever runs the artifact.
#
# The dev profile gives in-memory H2, so this needs no database service.
#
# Note /health always answers status "ok", so a 200 alone proves nothing: the
# assertion is `engine: available`, which comes from
# AppConfig.isOpenCvAvailable() and therefore fails unless the natives were
# bundled AND load on this platform. That is the whole point of running it for
# BOTH jars: a dependency bump that quietly breaks the slim profile (the
# excluded native filter stops matching, a classifier moves the .so) otherwise
# ships a jar that boots fine and cannot see a single frame.
#
# SMOKE_JAR picks which jar to boot — `full` (default, the cross-platform
# deployable) or `slim` (linux-x86_64 only, what CI and the Docker image use).
# Both get the same engine assertion, because both are supposed to have a
# working engine on this platform.
#
# Exits non-zero with the tail of the application log on any failure.
set -uo pipefail

# Namespaced on purpose. `PORT` is a common ambient variable and this sandbox
# has PORT=0, which Spring reads as "bind an ephemeral port" — the jar then
# starts on a random port, the poll loop never connects, and the run burns the
# full 240 s failing with a misleading "did not serve /health".
PORT="${SMOKE_PORT:-8081}"
PROFILE="${SMOKE_PROFILE:-dev}"
LOG="${SMOKE_LOG:-/tmp/app.log}"
WHICH_JAR="${SMOKE_JAR:-full}"

case "$WHICH_JAR" in
  full|slim) ;;
  *)
    echo "::error::SMOKE_JAR must be 'full' or 'slim', got '$WHICH_JAR'"
    exit 2
    ;;
esac

# Globs rather than `ls | grep`: the file names are fixed, and nullglob turns
# "nothing matched" into an empty array instead of the literal pattern, so a
# missing jar reports itself instead of booting a file that does not exist.
shopt -s nullglob
ALL_JARS=( target/pad-liveness-backend-*.jar )
shopt -u nullglob

if [ "$WHICH_JAR" = "slim" ]; then
  shopt -s nullglob
  JARS=( target/pad-liveness-backend-*-slim.jar )
  shopt -u nullglob
else
  # The -slim classifier is excluded here so a build that produced both jars
  # (see the `slim` profile in pom.xml) still boots the real deployable.
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
echo "smoke testing ($WHICH_JAR): $JAR"

# Output goes to a file, not this script's stdout: a backgrounded JVM holding
# the caller's pipe can keep the calling step alive long after the check is done.
java -jar "$JAR" --spring.profiles.active="$PROFILE" --server.port="$PORT" \
  > "$LOG" 2>&1 &
APP_PID=$!

# Never leave the JVM running — not on success, not on failure.
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

# Startup runs the full bean graph (Hibernate DDL + OpenCV natives + the ONNX
# session) and takes ~60 s locally, so poll rather than sleep.
BODY=""
for _ in $(seq 1 80); do
  if BODY=$(curl -sf -m 5 "http://127.0.0.1:$PORT/health" 2>/dev/null); then
    break
  fi
  # A jar that dies on startup is the common failure; fail on it immediately
  # instead of burning the whole 240 s budget first.
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "::error::packaged jar exited before /health responded"
    tail -40 "$LOG"
    exit 1
  fi
  sleep 3
done

if [ -z "$BODY" ]; then
  echo "::error::packaged jar did not serve /health within 240s"
  tail -40 "$LOG"
  exit 1
fi

echo "health: $BODY"
case "$BODY" in
  *'"engine":"available"'*) ;;
  *)
    echo "::error::OpenCV natives missing or unloadable in the packaged jar"
    tail -40 "$LOG"
    exit 1
    ;;
esac
echo "packaged jar booted and reports the engine available"