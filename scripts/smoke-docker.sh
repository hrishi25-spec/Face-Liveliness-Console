#!/usr/bin/env bash
# Build the image, boot it, and prove it actually serves traffic.
#
# The jar jobs cannot see this delivery path: the Dockerfile's COPY, its base
# image, the non-root user and the JVM flags exist only here, so a mistake in
# any of them ships green unless a container is actually started against it.
#
# One deliberate difference from scripts/smoke-jar.sh: the assertion is
# `status: ok`, NOT `engine: available`. The image is Alpine (musl) and the
# OpenCV native is glibc-linked, so it cannot load there — the app is designed
# to degrade (frames return 503, while config/audit, rate limiting and the
# console all keep working). Asserting the engine here would fail forever on a
# correct image. The engine assertion belongs to the jar smoke tests, which run
# on glibc.
#
# The image is built from the slim profile (see the Dockerfile), so the slim jar
# has to exist first; the script says so rather than letting docker report an
# opaque COPY failure.
#
# Exits non-zero with the tail of the container log on any failure, and always
# removes the container it started.
set -uo pipefail

IMAGE="${SMOKE_IMAGE:-pad-liveness-backend:ci}"
CONTAINER="${SMOKE_CONTAINER:-pad-liveness-smoke}"
PORT="${SMOKE_DOCKER_PORT:-8090}"
# Same reason as smoke-jar.sh: the dev profile is in-memory H2, so the smoke
# test needs no database service. The image's CMD is fixed at build time, so the
# profile goes in through the environment (Spring Boot reads SPRING_PROFILES_ACTIVE).
PROFILE="${SMOKE_PROFILE:-dev}"
WAIT_SECONDS="${SMOKE_DOCKER_WAIT:-240}"

# Version-independent: the version lives in the pom, the classifier does not.
shopt -s nullglob
# shellcheck disable=SC2206
SLIM_JARS=( target/pad-liveness-backend-*-slim.jar )
shopt -u nullglob
if [ "${#SLIM_JARS[@]}" -eq 0 ]; then
  echo "::error::no slim jar in target/ — the image is built from the slim profile"
  echo "         ./mvnw clean package -DskipTests -Pslim"
  exit 1
fi

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "building $IMAGE from ${SLIM_JARS[0]}"
docker build -t "$IMAGE" . || exit 1

# Drop a container left behind by an interrupted run before binding the port.
cleanup
docker run -d --name "$CONTAINER" -p "$PORT:8000" \
  -e SPRING_PROFILES_ACTIVE="$PROFILE" "$IMAGE" >/dev/null || exit 1

# The container must not run as root: it extracts OpenCV natives into /tmp and
# serves the console, and the image deliberately creates an unprivileged user.
RUN_AS=$(docker inspect -f '{{.Config.User}}' "$CONTAINER")
if [ -z "$RUN_AS" ] || [ "$RUN_AS" = "root" ] || [ "$RUN_AS" = "0" ]; then
  echo "::error::container runs as '$RUN_AS' — the image must drop privileges"
  docker logs "$CONTAINER" 2>&1 | tail -40
  exit 1
fi

# Startup runs the full bean graph and the ONNX session, so poll rather than
# sleep; a container that dies on boot fails immediately instead of burning the
# whole budget.
BODY=""
for _ in $(seq 1 $(( WAIT_SECONDS / 3 ))); do
  if BODY=$(curl -sf -m 5 "http://127.0.0.1:$PORT/health" 2>/dev/null); then
    break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
    echo "::error::container exited before /health responded"
    docker logs "$CONTAINER" 2>&1 | tail -40
    exit 1
  fi
  sleep 3
done

if [ -z "$BODY" ]; then
  echo "::error::container did not serve /health within ${WAIT_SECONDS}s"
  docker logs "$CONTAINER" 2>&1 | tail -40
  exit 1
fi

echo "health: $BODY"
case "$BODY" in
  *'"status":"ok"'*) ;;
  *)
    echo "::error::container /health did not report status ok"
    docker logs "$CONTAINER" 2>&1 | tail -40
    exit 1
    ;;
esac

# Report the sum of the layers, not `docker image inspect .Size`: that number
# folds in shared base-image accounting and came back as 606 MiB for an image
# whose layers add up to 387 MiB, which would make a payload regression
# invisible in the log. The layer sum is what the image actually puts on disk.
IMAGE_BYTES=$(docker history "$IMAGE" --format '{{.Size}}' | awk '
  function bytes(s) {
    if (s ~ /GB$/) return substr(s, 1, length(s) - 2) * 1073741824
    if (s ~ /MB$/) return substr(s, 1, length(s) - 2) * 1048576
    if (s ~ /kB$/) return substr(s, 1, length(s) - 2) * 1024
    return substr(s, 1, length(s) - 1)
  }
  { total += bytes($1) }
  END { printf "%d", total }')
echo "image size: $(( IMAGE_BYTES / 1048576 )) MiB of layers (runs as '$RUN_AS')"
echo "container booted from the image and serves /health"