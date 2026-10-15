#!/usr/bin/env bash
# MOSSIP Face Liveness & PAD — minimal one-command runner
# Usage: ./run.sh [--port N] [--profile dev|default] [--no-browser]
#        ./run.sh --help

set -euo pipefail

cd "$(dirname "$0")"

PORT=8000
PROFILE=dev
OPEN_BROWSER=1

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--port)     PORT="${2:-8000}"; shift 2 ;;
    --port=*)      PORT="${1#*=}"; shift ;;
    --profile)     PROFILE="${2:-dev}"; shift 2 ;;
    --profile=*)   PROFILE="${1#*=}"; shift ;;
    --no-browser)  OPEN_BROWSER=0; shift ;;
    -h|--help)     sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown: $1"; exit 1 ;;
  esac
done

# Find JDK 17+
java_major() {
  local v
  v="$("$1" -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')" || return 1
  case "$v" in
    1.*) printf '%s' "${v#1.}" | cut -d. -f1 ;;
    *)   printf '%s' "${v%%.*}" ;;
  esac
}
try_java() {
  [ -n "$1" ] && [ -x "$1" ] || return 1
  local major
  major="$(java_major "$1" 2>/dev/null || true)"
  [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null || return 1
  JAVA_BIN="$1"
}
find_java() {
  [ -n "${JAVA_HOME:-}" ] && try_java "$JAVA_HOME/bin/java" && echo "$JAVA_BIN" && return
  p="$(command -v java 2>/dev/null || true)"
  [ -n "$p" ] && try_java "$p" && echo "$JAVA_BIN" && return
  for p in /usr/lib/jvm/*/bin/java /opt/java/openjdk/bin/java /opt/homebrew/opt/openjdk@21/bin/java /opt/homebrew/opt/openjdk@17/bin/java /opt/homebrew/opt/openjdk/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
    try_java "$p" && echo "$JAVA_BIN" && return
  done
  return 1
}

JAVA_BIN=$(find_java) || { echo "Need JDK 17+. Install: brew install openjdk@21 / apt install openjdk-21-jdk / winget install EclipseAdoptium.Temurin.21.JDK"; exit 1; }

# Build if needed (uses Maven wrapper)
JAR="target/pad-liveness-backend-1.0.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "Building..."; if [ -x "./mvnw" ]; then ./mvnw -q package -DskipTests; else mvn -q package -DskipTests; fi; }

# Check port
(exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && { echo "Port $PORT in use"; exit 1; }

# Start
"$JAVA_BIN" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -jar "$JAR" --spring.profiles.active="$PROFILE" --server.port="$PORT" &
PID=$!

trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null' EXIT INT TERM

# Wait for health
for _ in {1..60}; do
  curl --noproxy "*" -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 1
done

ENGINE=$(curl --noproxy "*" -fs "http://127.0.0.1:$PORT/health" 2>/dev/null | sed -n 's/.*"engine":"\([^"]*\)".*/\1/p')

echo ""
echo "✓ Service running on http://localhost:$PORT"
echo "  Console:     http://localhost:$PORT/"
echo "  API docs:    http://localhost:$PORT/swagger-ui/index.html"
echo "  Health:      http://localhost:$PORT/health"
echo "  Engine:      ${ENGINE:-unknown}"
if [ "$PROFILE" = "dev" ]; then
  echo "  Sign in:     supervisor / supervisor123"
fi
echo ""
[ "$OPEN_BROWSER" = 1 ] && { xdg-open "http://localhost:$PORT/" 2>/dev/null || open "http://localhost:$PORT/" 2>/dev/null || start "" "http://localhost:$PORT/" 2>/dev/null; }

wait $PID
