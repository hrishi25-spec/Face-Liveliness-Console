#!/usr/bin/env bash
#
# MOSSIP Face Liveness & PAD — one-command launcher.
#
#   ./start.sh                 build if needed, start the service, open the console
#   ./start.sh --no-build      skip Maven and launch the existing jar (fast restart)
#   ./start.sh --rebuild       clean build + full test suite
#   ./start.sh --port 9000     serve on a different port
#   ./start.sh --no-browser    don't open a browser
#   ./start.sh --profile prod  run against PostgreSQL instead of H2
#
# Works on macOS, Linux and Windows Git Bash / MSYS2 / Cygwin.
#
set -euo pipefail

# Re-exec under bash if invoked as `sh start.sh` (we rely on bash features).
if [ -z "${BASH_VERSION:-}" ]; then
  exec bash "$0" "$@"
fi

cd "$(dirname "$0")"

PORT="${MOSSIP_PORT:-8000}"
PROFILE="dev"
DO_BUILD=1
DO_CLEAN=0
RUN_TESTS=0
OPEN_BROWSER=1
JAR="target/pad-liveness-backend-1.0.0-SNAPSHOT.jar"
LOG="target/mossip-startup.log"
APP_PID=""

# ---------------------------------------------------------------- presentation
if [ -t 1 ]; then
  B=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GRN=$'\033[32m'
  YEL=$'\033[33m'; BLU=$'\033[34m'; OFF=$'\033[0m'
else
  B=""; DIM=""; RED=""; GRN=""; YEL=""; BLU=""; OFF=""
fi
say()  { printf '%s\n' "$*"; }
info() { printf '%s\n' "${BLU}▸${OFF} $*"; }
ok()   { printf '%s\n' "${GRN}✓${OFF} $*"; }
warn() { printf '%s\n' "${YEL}!${OFF} $*"; }
die()  { printf '%s\n' "${RED}✗ $*${OFF}" >&2; exit 1; }

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -p|--port)     [ $# -ge 2 ] || die "--port needs a value"; PORT="$2"; shift 2 ;;
    --port=*)      PORT="${1#*=}"; shift ;;
    --profile)     [ $# -ge 2 ] || die "--profile needs a value"; PROFILE="$2"; shift 2 ;;
    --profile=*)   PROFILE="${1#*=}"; shift ;;
    --no-build)    DO_BUILD=0; shift ;;
    --rebuild)     DO_BUILD=1; DO_CLEAN=1; RUN_TESTS=1; shift ;;
    --test)        RUN_TESTS=1; shift ;;
    --no-browser)  OPEN_BROWSER=0; shift ;;
    -h|--help)     usage; exit 0 ;;
    *)             die "Unknown option: $1 (try --help)" ;;
  esac
done

say ""
say "${B}MOSIP Face Liveness & PAD Service${OFF} ${DIM}— launcher${OFF}"
say "${DIM}$(uname -s) $(uname -m) · port $PORT · profile $PROFILE${OFF}"
say ""

mkdir -p target

# ------------------------------------------------------------------ 1. find JDK
JAVA_BIN=""
java_major() {  # $1 = path to java
  local v
  v="$("$1" -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')" || return 1
  case "$v" in
    1.*) printf '%s' "$(printf '%s' "${v#1.}" | cut -d. -f1)" ;;
    *)   printf '%s' "${v%%.*}" ;;
  esac
}
try_java() {
  [ -n "$1" ] && [ -x "$1" ] || return 1
  local mj; mj="$(java_major "$1" 2>/dev/null || true)"
  [ -n "$mj" ] && [ "$mj" -ge 17 ] 2>/dev/null || return 1
  JAVA_BIN="$1"; return 0
}

info "Looking for a JDK 17 or newer…"
if [ -n "${JAVA_HOME:-}" ]; then try_java "$JAVA_HOME/bin/java" || true; fi
if [ -z "$JAVA_BIN" ] && [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
  try_java "$(/usr/libexec/java_home -v 17 2>/dev/null || true)/bin/java" || true
fi
if [ -z "$JAVA_BIN" ]; then
  try_java "$(command -v java 2>/dev/null || true)" || true
fi
if [ -z "$JAVA_BIN" ]; then
  for c in /usr/lib/jvm/*/bin/java /opt/java/openjdk/bin/java \
           /opt/homebrew/opt/openjdk@21/bin/java /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java; do
    try_java "$c" || true
    [ -n "$JAVA_BIN" ] && break
  done
fi

[ -n "$JAVA_BIN" ] || die "No JDK 17+ found.
  Install one, then either put 'java' on your PATH or set JAVA_HOME.
    macOS:   brew install openjdk@21
    Ubuntu:  sudo apt install openjdk-21-jdk
    Windows: winget install EclipseAdoptium.Temurin.21.JDK"

export JAVA_HOME="${JAVA_HOME:-$(cd "$(dirname "$JAVA_BIN")/.." && pwd)}"
ok "JDK $(java_major "$JAVA_BIN") → $JAVA_BIN"

# ---------------------------------------------------------------- 2. find Maven
MVN=""
if [ -x "./mvnw" ]; then MVN="./mvnw"; elif command -v mvn >/dev/null 2>&1; then MVN="mvn"; fi

# ------------------------------------------------------------------- 3. build
if [ "$DO_BUILD" = 1 ]; then
  [ -n "$MVN" ] || die "Neither ./mvnw nor 'mvn' is available, so the jar cannot be built.
  Either install Maven (https://maven.apache.org) or run './start.sh --no-build'
  once a prebuilt $JAR exists."
  if [ "$DO_CLEAN" = 1 ]; then
    info "Clean build (this also runs the full test suite)…"
    "$MVN" clean package
  elif [ "$RUN_TESTS" = 1 ]; then
    info "Building and running tests…"
    "$MVN" package
  else
    info "Building (skipping tests — use --test to include them)…"
    "$MVN" -q package -DskipTests
  fi
  [ -f "$JAR" ] || die "Build finished but $JAR is missing."
  ok "Build complete"
fi

[ -f "$JAR" ] || die "$JAR not found. Run './start.sh' without --no-build first."

# ------------------------------------------------------------- 4. check the port
port_in_use() {
  (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
}
if port_in_use; then
  die "Port $PORT is already in use.
  Stop the other process, or start on a different port:  ./start.sh --port 8001"
fi
ok "Port $PORT is free"

# ------------------------------------------------------------------ 5. run it
cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    printf '\n'
    info "Stopping service…"
    kill "$APP_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 0.4
    done
    kill -9 "$APP_PID" 2>/dev/null || true
    ok "Stopped"
  fi
}
trap cleanup EXIT INT TERM

info "Starting service (logs → $LOG)…"
START_TS=$(date +%s)
"$JAVA_BIN" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -jar "$JAR" --spring.profiles.active="$PROFILE" --server.port="$PORT" \
  > "$LOG" 2>&1 &
APP_PID=$!

http_ok() {
  if command -v curl >/dev/null 2>&1; then
    curl --noproxy "*" -fs -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
  fi
}

# ------------------------------------------------------------------- 6. wait
READY=0
for _ in $(seq 1 120); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
  if http_ok; then READY=1; break; fi
  sleep 1
done

if [ "$READY" != 1 ]; then
  printf '\n'
  if grep -qiE "port .* (was )?already in use|Web server failed to start" "$LOG" 2>/dev/null; then
    warn "The service could not bind port $PORT — something else took it."
    say  "  Retry on another port:  ./start.sh --port 8001"
  else
    warn "The service failed to start. Last lines of $LOG:"
    say ""
    tail -n 25 "$LOG" 2>/dev/null || true
  fi
  exit 1
fi

ENGINE="$(curl --noproxy "*" -fs -m 2 "http://127.0.0.1:$PORT/health" 2>/dev/null | sed -n 's/.*"engine":"\([^"]*\)".*/\1/p')"
[ -n "$ENGINE" ] || ENGINE="unknown"

URL="http://localhost:$PORT/"
say ""
ok "Service is up (started in $(( $(date +%s) - START_TS ))s)"
say "  ${B}Console${OFF}      $URL"
say "  API docs${OFF}     http://localhost:$PORT/swagger-ui/index.html"
say "  Health${OFF}       http://localhost:$PORT/health"
if [ "$PROFILE" = "dev" ]; then
  say "  ${B}Sign in${OFF}      supervisor / supervisor123"
fi
if [ "$ENGINE" = "available" ]; then
  say "  Engine${OFF}       ${GRN}available${OFF} (OpenCV native library loaded)"
else
  say "  Engine${OFF}       ${YEL}$ENGINE${OFF} — frame endpoints return 503 on this platform"
fi
say ""
say "  ${DIM}Press Ctrl+C to stop.${OFF}"
say ""

if [ "$OPEN_BROWSER" = 1 ]; then
  case "$(uname -s)" in
    Darwin)               open "$URL" >/dev/null 2>&1 || true ;;
    Linux)                xdg-open "$URL" >/dev/null 2>&1 || true ;;
    MINGW*|MSYS*|CYGWIN*) cmd.exe /c start "" "$URL" >/dev/null 2>&1 || true ;;
  esac
fi

wait "$APP_PID" || true
