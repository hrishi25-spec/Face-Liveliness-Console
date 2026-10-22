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
# Before booting, the payload is compared with pom.xml: BOOT-INF/lib must be
# the runtime classpath Maven resolves, so a repackage that drops a dependency
# or ships one the pom never declared fails here instead of in the field. See
# "payload check" below; SMOKE_CLASSPATH_FILE and SMOKE_REFRESH_CLASSPATH
# override the resolution.
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

# ------------------------------------------------------------- payload check
#
# The jar is the only thing that ships, and the suite cannot see it: surefire
# runs against target/classes with the full ~/.m2 classpath, so a repackage
# that omits a jar or includes the wrong one is invisible there. BOOT-INF/lib
# is therefore compared with the runtime classpath Maven resolves from
# pom.xml — the same resolution spring-boot-maven-plugin repackages — in both
# directions:
#
#   * declared but missing: an <exclude> that now matches too much, a plugin
#     change, a repackage that lost a jar. The service still starts and fails
#     at the first request that touches the class.
#   * shipped but undeclared: a provided dependency whose exclusion was
#     removed (JavaFX is exactly this case), a test jar, an artifact left over
#     from an earlier build. This is the direction that leaks code and
#     megabytes into a release.
#
# Names, not coordinates: that is how the repackager writes entries and what
# Maven resolves, so a version drift appears as one missing name plus one
# unexpected one.
#
# The classpath comes from Maven rather than from parsing pom.xml because the
# versions are mostly the parent's: spring-boot-starter-parent's dependency
# management decides what these jars are called, and a second, bash-shaped
# parser would be a second opinion nobody asked for.

if command -v unzip >/dev/null 2>&1; then
    jar_entries() { unzip -Z1 "$1"; }
elif command -v jar >/dev/null 2>&1; then
    jar_entries() { jar tf "$1"; }
else
    echo "::error::need unzip or jar on PATH to inspect BOOT-INF/lib"
    exit 1
fi

CP_FILE="${SMOKE_CLASSPATH_FILE:-target/smoke-classpath.txt}"
RESOLVE_CP=0
if [ -n "${SMOKE_CLASSPATH_FILE:-}" ]; then
    [ -f "$CP_FILE" ] || { echo "::error::SMOKE_CLASSPATH_FILE=$CP_FILE does not exist"; exit 1; }
elif [ "${SMOKE_REFRESH_CLASSPATH:-0}" = 1 ] || [ ! -f "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
    RESOLVE_CP=1
fi

if [ "$RESOLVE_CP" = 1 ]; then
    if [ -f pom.xml ] && [ -x ./mvnw ]; then
        echo "resolving the runtime classpath from pom.xml"
        # `includeScope`, not `mdep.includeScope`: the prefixed spelling is
        # ignored by this plugin version, which then resolves the *test*
        # classpath and reports every test jar as undeclared.
        if ! ./mvnw -q -B -DincludeScope=runtime -Dmdep.outputFile="$CP_FILE" dependency:build-classpath; then
            echo "::error::dependency:build-classpath failed — cannot verify BOOT-INF/lib"
            exit 1
        fi
    else
        # A jar downloaded from a release has no pom beside it; the boot and
        # engine assertions still apply.
        echo "::warning::no pom.xml and ./mvnw in $(pwd) — skipping the BOOT-INF/lib comparison"
        CP_FILE=""
    fi
fi

if [ -n "$CP_FILE" ]; then
    EXPECTED="target/smoke-payload-expected-$WHICH_JAR.txt"
    ACTUAL="target/smoke-payload-actual-$WHICH_JAR.txt"

    : > "$EXPECTED"
    TOTAL=0
    METADATA_ONLY=0
    while IFS= read -r artifact || [ -n "$artifact" ]; do
        [ -n "$artifact" ] || continue
        TOTAL=$((TOTAL + 1))
        if ! entries=$(jar_entries "$artifact" 2>/dev/null); then
            echo "::error::cannot list $artifact — the resolved classpath is unreadable"
            exit 1
        fi
        # A jar with nothing but manifest/LICENCE/NOTICE is not payload, and
        # the repackager leaves it out: Boot 4's starter jars are exactly that,
        # which is why the jar carries ten fewer entries than the classpath
        # has. META-INF/resources is the exception — webjars such as swagger-ui
        # live entirely under it and are shipped.
        #
        # Here-string, not a pipe: with `pipefail`, `grep -q` exiting on the
        # first match kills the writer with SIGPIPE and turns a match into a
        # pipeline failure — which silently skipped the largest jars.
        grep -qE '\.class$|^META-INF/resources/' <<< "$entries" \
            || { METADATA_ONLY=$((METADATA_ONLY + 1)); continue; }
        basename "$artifact" >> "$EXPECTED"
    done < <(tr ':' '\n' < "$CP_FILE")

    # lombok is `optional` in the pom, so Maven keeps it on the runtime
    # classpath, but it is an annotation processor and the plugin excludes it
    # from the jar. The slim profile additionally excludes the two jars it
    # unpacks into BOOT-INF/classes: their jars are absent by design, their
    # classes are asserted below.
    case "$WHICH_JAR" in
        slim) EXCLUDED='^(lombok|opencv|onnxruntime)-' ;;
        *)    EXCLUDED='^lombok-' ;;
    esac
    # The profile does not change the dependency set — slim only repackages and
    # unpacks — so one resolution covers both jars.
    grep -vE "$EXCLUDED" "$EXPECTED" | LC_ALL=C sort > "$EXPECTED.sorted"
    CLASHES=$(uniq -d "$EXPECTED.sorted")
    if [ -n "$CLASHES" ]; then
        echo "::error::two resolved artifacts share a file name, so the comparison is ambiguous:"
        printf '%s\n' "$CLASHES"
        exit 1
    fi

    jar_entries "$JAR" | sed -n 's#^BOOT-INF/lib/\([^/][^/]*\)$#\1#p' | LC_ALL=C sort > "$ACTUAL"
    DUPLICATES=$(uniq -d "$ACTUAL")
    if [ -n "$DUPLICATES" ]; then
        echo "::error::BOOT-INF/lib contains the same jar name more than once:"
        printf '%s\n' "$DUPLICATES"
        exit 1
    fi

    # spring-boot-maven-plugin injects this one itself for
    # `-Djarmode=layertools`; it is not a pom dependency, so it cannot appear
    # on the classpath side of the comparison.
    grep -v '^spring-boot-jarmode-tools-' "$ACTUAL" > "$ACTUAL.compared"

    MISSING=$(LC_ALL=C comm -23 "$EXPECTED.sorted" "$ACTUAL.compared")
    UNDECLARED=$(LC_ALL=C comm -13 "$EXPECTED.sorted" "$ACTUAL.compared")
    if [ -n "$MISSING" ] || [ -n "$UNDECLARED" ]; then
        if [ -n "$MISSING" ]; then
            echo "::error::declared runtime dependencies missing from BOOT-INF/lib:"
            printf '%s\n' "$MISSING" | sed 's/^/  /'
            echo "         the repackage dropped them — fix the <excludes> or the build, not this check"
        fi
        if [ -n "$UNDECLARED" ]; then
            echo "::error::jars in BOOT-INF/lib that pom.xml does not put on the runtime classpath:"
            printf '%s\n' "$UNDECLARED" | sed 's/^/  /'
            echo "         a provided/test artifact or a leftover from an earlier build"
        fi
        exit 1
    fi
    SHIPPED=$(wc -l < "$ACTUAL.compared")
    NOT_BY_DESIGN=$((TOTAL - METADATA_ONLY - SHIPPED))
    echo "payload: ${TOTAL} declared runtime artifacts -> ${SHIPPED} in BOOT-INF/lib (${METADATA_ONLY} manifest-only starters, ${NOT_BY_DESIGN} excluded by design)"

    # The comparison proves the opencv/onnxruntime *jars* are gone from slim;
    # only these assertions prove the classes and the linux natives replaced
    # them, and that the excluded platforms really left with them.
    if [ "$WHICH_JAR" = slim ]; then
        SLIM_ENTRIES=$(jar_entries "$JAR")
        require_entry() {
            grep -qE "$1" <<< "$SLIM_ENTRIES" \
                || { echo "::error::slim jar is missing $2 — the unpack filter dropped it"; exit 1; }
        }
        forbid_entry() {
            local count
            count=$(printf '%s\n' "$SLIM_ENTRIES" | grep -cE "$1")
            [ "$count" -eq 0 ] \
                || { echo "::error::slim jar still carries $count entries for $2 — the exclude filter stopped matching"; exit 1; }
        }
        require_entry '^BOOT-INF/classes/org/opencv/core/Mat\.class$' 'the OpenCV bindings (org/opencv/core/Mat.class)'
        require_entry '^BOOT-INF/classes/nu/pattern/opencv/linux/x86_64/.*\.so$' 'the linux/x86_64 OpenCV native'
        require_entry '^BOOT-INF/classes/ai/onnxruntime/native/linux-x64/' 'the linux-x64 ONNX Runtime native'
        forbid_entry '^BOOT-INF/classes/nu/pattern/opencv/(osx|windows|linux/x86_32|linux/ARMv7|linux/ARMv8)/' "other platforms' OpenCV natives"
        forbid_entry '^BOOT-INF/classes/ai/onnxruntime/native/(osx-aarch64|linux-aarch64|win-x64)/' "other platforms' ONNX Runtime natives"
        echo "payload: slim natives unpacked, only linux/x86_64 present"
    fi
fi

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