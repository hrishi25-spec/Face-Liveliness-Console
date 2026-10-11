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
find_java() {
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && { JAVA_HOME/bin/java -version 2>&1 | grep -q 'version "1[7-9]\.' && echo "$JAVA_HOME/bin/java" && return; }
  command -v java >/dev/null && java -version 2>&1 | grep -q 'version "1[7-9]\.' && command -v java && return
  for p in /usr/lib/jvm/*/bin/java /opt/java/openjdk/bin/java /opt/homebrew/opt/openjdk@21/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
    [ -x "$p" ] && "$p" -version 2>&1 | grep -q 'version "1[7-9]\.' && echo "$p" && return
  done
  return 1
}

JAVA_BIN=$(find_java) || { echo "Need JDK 17+. Install: brew install openjdk@21 / apt install openjdk-21-jdk / winget install EclipseAdoptium.Temurin.21.JDK"; exit 1; }

# Build if needed (uses Maven wrapper)
JAR="target/pad-liveness-backend-1.0.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo "Building..."; ./mvnw -q package -DskipTests; }

# Check port
(exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && { echo "Port $PORT in use"; exit 1; }

# Start
"$JAVA_BIN" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -jar "$JAR" --spring.profiles.active="$PROFILE" --server.port="$PORT" &
PID=$!

trap 'kill $PID 2>/dev/null; wait $PID 2>/dev/null' EXIT INT TERM

# Wait for health
for _ in {1..60}; do
  curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 1
done

ENGINE=$(curl -fs "http://127.0.0.1:$PORT/health" | sed -n 's/.*"engine":"\([^"]*\)".*/\1/p')

echo ""
echo "✓ Service running on http://localhost:$PORT"
echo "  Console:     http://localhost:$PORT/"
echo "  API docs:    http://localhost:$PORT/swagger-ui/index.html"
echo "  Health:      http://localhost:$PORT/health"
echo "  Engine:      ${ENGINE:-unknown}"
echo ""
[ "$OPEN_BROWSER" = 1 ] && { xdg-open "http://localhost:$PORT/" 2>/dev/null || open "http://localhost:$PORT/" 2>/dev/null || start "" "http://localhost:$PORT/" 2>/dev/null; }

wait $PID
