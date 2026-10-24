#!/usr/bin/env bash
#
# Boot the packaged unit under the *oldest* systemd this project supports.
#
# `scripts/smoke-linux-packages.sh` boots the unit on whatever the CI runner is
# running — today that is the newest Ubuntu LTS. That proves the sandbox works
# there, and says nothing about the hosts the .deb is actually installed on,
# which is where the failure mode lives: systemd logs
#
#     Unknown lvalue 'ProtectProc' in section 'Service'
#
# once at load time and then runs the unit *without* that directive. No command
# fails, `systemd-analyze verify` is happy, the service answers /health, and the
# hardening the unit claims is simply absent. The same is true of a directive
# the kernel cannot enforce: systemd applies what the kernel offers and ignores
# the rest, again without failing anything.
#
# So this gate boots the unit on the oldest systemd the project claims to
# support, and fails if that host does not understand a single directive in it.
# Adding a directive that only exists in a newer systemd turns this job red —
# which is the point: the floor has to move deliberately, with the requirement
# written down, not by accident.
#
# How it runs
#   Each host is an OCI image of a distribution whose systemd is at the floor
#   (default: Ubuntu 22.04 → systemd 249, Debian 12 → systemd 252). Inside it,
#   this installs the distribution's systemd, boots it as PID 1, installs
#   `packaging/linux/pad-liveness.service` **unmodified**, starts it, and asks
#   systemd itself what it applied. The application image is the built jar,
#   mounted read-only with the host JDK and launched by a two-line stand-in for
#   jpackage's launcher — the unit, its directives and the sandbox are the
#   shipped ones, which is what this gate is about. The installer payload is
#   covered by scripts/smoke-linux-packages.sh.
#
# What it asserts, per host
#   * every sandbox directive is a property that host's systemd knows at all —
#     `systemctl show -p <key>` must print `<key>=`; an unknown directive prints
#     nothing, which is exactly the silent ignore;
#   * the value it reports is the value in the unit (word order normalized for
#     RestrictAddressFamilies, whose order systemd does not preserve);
#   * the boot journal carries no `Unknown lvalue`/`Unknown key` warning for it;
#   * the service reaches active, /health reports the engine available, the
#     console serves HTML, DynamicUser and the StateDirectory exist, and a
#     restart comes back — an older sandbox that breaks the JVM is a failure,
#     not a pass;
#   * the host kernel is at least the version the unit's newest directive needs
#     (5.8 for ProtectProc/ProcSubset). A container shares the host kernel, so
#     this is a precondition of the gate, not a proof about the floor host.
#
# Requirements: docker, a built jar, a JDK 17. Exits non-zero on the first host
# that ignores a directive, printing the directive, the value systemd reported
# and the directive's line in the unit.
set -uo pipefail

NAME="pad-liveness"
UNIT_FILE="${SMOKE_COMPAT_UNIT:-packaging/linux/pad-liveness.service}"
IMAGES="${SMOKE_COMPAT_IMAGES:-debian:11 ubuntu:22.04}"
PORT="${SMOKE_COMPAT_PORT:-8094}"
JAR="${SMOKE_COMPAT_JAR:-}"
KERNEL_FLOOR="5.8"
CONTAINER=""

fail() {
    echo "::error::$*"
    exit 1
}

cleanup() {
    [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail "docker is required to boot an older distribution's systemd"
[ -f "$UNIT_FILE" ] || fail "no unit file at $UNIT_FILE"

if [ -z "$JAR" ]; then
    shopt -s nullglob
    CANDIDATES=( target/pad-liveness-backend-*-slim.jar target/pad-liveness-backend-*.jar )
    shopt -u nullglob
    [ "${#CANDIDATES[@]}" -gt 0 ] \
        || fail "no built jar in target/ — build one: ./mvnw package -DskipTests -Pslim (or set SMOKE_COMPAT_JAR)"
    JAR="${CANDIDATES[0]}"
fi
[ -f "$JAR" ] || fail "no such jar: $JAR"

# The kernel floor is checked on the host, because a container cannot change it.
KERNEL=$(uname -r)
KERNEL_MAJOR=$(printf '%s' "$KERNEL" | sed -E 's/^([0-9]+)\..*/\1/')
KERNEL_MINOR=$(printf '%s' "$KERNEL" | sed -E 's/^[0-9]+\.([0-9]+).*/\1/')
FLOOR_MAJOR=${KERNEL_FLOOR%%.*}
FLOOR_MINOR=${KERNEL_FLOOR#*.}
if [ "$KERNEL_MAJOR" -lt "$FLOOR_MAJOR" ] \
    || { [ "$KERNEL_MAJOR" -eq "$FLOOR_MAJOR" ] && [ "$KERNEL_MINOR" -lt "$FLOOR_MINOR" ]; }; then
    fail "kernel $KERNEL is older than $KERNEL_FLOOR, which the unit's ProtectProc/ProcSubset directives need — systemd would ignore them silently on this host"
fi

# The directives under test: the unit's sandbox settings, in file order, so a
# failure can name the line. Environment/ExecStart/Restart* are not sandbox
# settings and do not round-trip through `systemctl show` the same way.
mapfile -t DIRECTIVES < <(
    awk '
        /^\[Service\]/ { in_service = 1; next }
        /^\[/         { in_service = 0 }
        in_service && /^[A-Za-z][A-Za-z0-9]*=/ { print }
    ' "$UNIT_FILE" \
    | grep -vE '^(Environment|ExecStart|WorkingDirectory|Type|Restart|RestartSec|SuccessExitStatus)='
)
[ "${#DIRECTIVES[@]}" -gt 0 ] || fail "no sandbox directives found in $UNIT_FILE"

echo "compat: $(basename "$JAR"), unit ${#DIRECTIVES[@]} sandbox directives, kernel $KERNEL (floor $KERNEL_FLOOR)"

# Normalized value comparison. systemd canonicalizes some values — most notably
# it does not preserve the order of RestrictAddressFamilies — so compare words
# as a sorted set, case-insensitively.
normalize() {
    tr 'A-Z' 'a-z' | tr ',' '\n' | tr -s ' \t' '\n' | sed '/^$/d' | sort | tr '\n' ' '
}

# Directives whose reported value is a resolution rather than an echo: systemd
# expands @system-service into the syscall list it resolved on this host, so the
# text never matches. Presence plus a non-empty result is the assertion that
# matters, and it still fails on a host that cannot resolve the set.
resolved_directive() {
    [ "$1" = "SystemCallFilter" ]
}

# Errno names come back as numbers. Only what the unit actually uses is mapped.
resolved_value() {
    case "$1=$2" in
        SystemCallErrorNumber=EPERM) echo "1" ;;
        *) echo "" ;;
    esac
}

check_host() {
    local image="$1"
    local port="$2"
    local log
    log="$(mktemp -t "compat-$(printf '%s' "$image" | tr '/:' '--').XXXXXX.log")"

    echo
    echo "== $image"

    # One container, two phases: the distribution's `sh` installs its systemd,
    # then execs it as PID 1. Installing inside the boot container keeps the
    # systemd version pinned to the distribution rather than baked into an
    # image this repository would have to rebuild and host.
    CONTAINER="pad-liveness-compat-$(date +%s)-$$"
    # --cgroupns=private is what makes systemd usable here: it gets its own
    # writable cgroup tree. Binding the host's /sys/fs/cgroup into that
    # namespace as well gives it a view it cannot manage, and PID 1 exits.
    if ! docker run -d --name "$CONTAINER" \
        --privileged --cgroupns=private \
        --tmpfs /run --tmpfs /run/lock \
        -v "$(realpath "$JAR"):/opt/pad-liveness/lib/app.jar:ro" \
        -p "127.0.0.1:$port:8000" \
        "$image" \
        /bin/sh -c 'apt-get update -qq >/dev/null 2>&1
                    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends systemd openjdk-17-jre-headless >/dev/null 2>&1
                    exec /lib/systemd/systemd' > "$log" 2>&1; then
        tail -20 "$log"
        fail "$image: could not start the container"
    fi

    # systemd is up when it can name its own version and its state is no longer
    # `starting`. Both checks run inside the guest shell: `docker exec` reports
    # its own "executable not found" on stdout while the packages are still
    # being unpacked, and that text must not be mistaken for a version.
    local version=""
    for _ in $(seq 1 120); do
        if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
            docker logs "$CONTAINER" 2>&1 | tail -20
            fail "$image: the container exited before systemd started"
        fi
        version=$(docker exec "$CONTAINER" /bin/sh -c 'command -v systemctl >/dev/null && systemctl --version' 2>/dev/null \
            | head -1 | tr -d '\r')
        case "$version" in
            systemd\ [0-9]*) break ;;
            *) version="" ;;
        esac
        sleep 2
    done
    [ -n "$version" ] \
        || { docker logs "$CONTAINER" 2>&1 | tail -30; fail "$image: systemd did not install and start inside the container"; }
    echo "   $version"

    # Give the boot a moment to settle before the unit is loaded, so a start
    # failure is the unit's and not a race with basic.target.
    local state=""
    for _ in $(seq 1 60); do
        state=$(docker exec "$CONTAINER" systemctl is-system-running 2>/dev/null | tr -d '\r')
        case "$state" in
            running|degraded) break ;;
            *) state="" ;;
        esac
        sleep 1
    done
    [ -n "$state" ] || { docker exec "$CONTAINER" systemctl list-units --failed --no-pager 2>&1 | head -20; fail "$image: systemd did not finish booting"; }
    echo "   state: $state"

    # The host's JDK is not copied in: a JDK built for this distribution's glibc
    # (Arch's, for instance) fails on an older one with `GLIBC_2.38 not found`,
    # which would make the gate report a sandbox failure that is really a host
    # artifact. The distribution's own Java 17 is what a host would run.
    docker exec "$CONTAINER" /bin/sh -c 'command -v java >/dev/null' \
        || fail "$image: no Java 17 in the image (openjdk-17-jre-headless did not install)"

    # The unit as shipped, plus a stand-in for jpackage's launcher at the path
    # the unit's ExecStart names. The unit itself is not edited.
    docker cp "$UNIT_FILE" "$CONTAINER:/usr/lib/systemd/system/$NAME.service" >/dev/null \
        || fail "$image: could not install the unit into the container"
    docker exec "$CONTAINER" /bin/sh -c "
        mkdir -p /opt/pad-liveness/bin
        printf '#!/bin/sh\nexec /usr/bin/java -jar /opt/pad-liveness/lib/app.jar \"\$@\"\n' \
            > /opt/pad-liveness/bin/pad-liveness
        chmod 0755 /opt/pad-liveness/bin/pad-liveness
        systemctl daemon-reload
    " >/dev/null 2>&1 || fail "$image: could not install the launcher or reload systemd"

    # ------------------------------------------------- the silent-ignore check
    #
    # `systemctl show -p Foo` prints nothing for a property this systemd does
    # not have, and `Foo=` for a property it has — including one whose value is
    # empty, like CapabilityBoundingSet=. That is the difference between "the
    # unit asks for nothing here" and "this host never heard of the directive".
    local ignored=() mismatched=()
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        local key="${line%%=*}"
        local expected="${line#*=}"
        local reported
        reported=$(docker exec "$CONTAINER" systemctl show "$NAME" -p "$key" 2>/dev/null | tr -d '\r' || true)
        if [ -z "$reported" ]; then
            ignored+=("$key=$expected")
            continue
        fi
        local actual="${reported#*=}"
        if resolved_directive "$key"; then
            [ -n "$actual" ] || mismatched+=("$key: unit says '$expected', systemd resolved nothing")
            continue
        fi
        local resolved
        resolved=$(resolved_value "$key" "$expected")
        if [ -n "$resolved" ]; then
            [ "$(printf '%s' "$actual" | normalize)" = "$(printf '%s' "$resolved" | normalize)" ] \
                || mismatched+=("$key: unit says '$expected', systemd reports '$actual'")
            continue
        fi
        if [ "$(printf '%s' "$actual" | normalize)" != "$(printf '%s' "$expected" | normalize)" ]; then
            mismatched+=("$key: unit says '$expected', systemd reports '$actual'")
        fi
    done <<< "$(printf '%s\n' "${DIRECTIVES[@]}")"

    if [ "${#ignored[@]}" -gt 0 ]; then
        for entry in "${ignored[@]}"; do
            printf '   ignores %s\n' "$entry"
        done
        fail "$image ($version) does not understand ${#ignored[@]} directive(s) in $UNIT_FILE — it runs the unit without them. Raise the supported floor deliberately, or drop the directive."
    fi
    if [ "${#mismatched[@]}" -gt 0 ]; then
        for entry in "${mismatched[@]}"; do
            printf '   reports %s\n' "$entry"
        done
        fail "$image ($version) applies different values than the unit declares"
    fi
    echo "   sandbox: all ${#DIRECTIVES[@]} directives understood and applied"

    # ------------------------------------------------------------ boots for real
    docker exec "$CONTAINER" systemctl start "$NAME" >/dev/null 2>&1 \
        || { docker exec "$CONTAINER" journalctl -u "$NAME" --no-pager 2>&1 | tail -40; fail "$image: systemctl start $NAME failed"; }

    local body=""
    for _ in $(seq 1 90); do
        body=$(curl -sf -m 5 "http://127.0.0.1:$port/health" 2>/dev/null) && break
        body=""
        if ! docker exec "$CONTAINER" systemctl is-active --quiet "$NAME" 2>/dev/null; then
            docker exec "$CONTAINER" journalctl -u "$NAME" --no-pager 2>&1 | tail -40
            fail "$image: the unit exited before /health answered — the older sandbox blocks something the JVM needs"
        fi
        sleep 2
    done
    [ -n "$body" ] || { docker exec "$CONTAINER" journalctl -u "$NAME" --no-pager 2>&1 | tail -40; fail "$image: /health did not answer within 180s"; }
    case "$body" in
        *'"engine":"available"'*) ;;
        *) fail "$image: the unit reports the engine unavailable under this sandbox: $body" ;;
    esac
    echo "   health: $body"

    local content_type
    content_type=$(curl -sf -m 10 -o /dev/null -w '%{content_type}' "http://127.0.0.1:$port/" 2>/dev/null) || content_type=""
    case "$content_type" in
        text/html*) ;;
        *) fail "$image: the console served '${content_type:-nothing}', expected text/html" ;;
    esac
    echo "   console: / served $content_type"

    [ "$(docker exec "$CONTAINER" systemctl show "$NAME" -p DynamicUser --value 2>/dev/null | tr -d '\r')" = "yes" ] \
        || fail "$image: the unit is not running under DynamicUser"
    docker exec "$CONTAINER" test -d "/var/lib/$NAME" \
        || fail "$image: /var/lib/$NAME was not created from StateDirectory"
    echo "   user: DynamicUser, state dir /var/lib/$NAME"

    # A directive this systemd rejects only at runtime leaves a parse warning in
    # the journal; catch it even when `systemctl show` happens to know the key.
    if docker exec "$CONTAINER" journalctl -b --no-pager 2>/dev/null | grep -qE "Unknown (lvalue|key) .*($NAME|Service)"; then
        docker exec "$CONTAINER" journalctl -b --no-pager 2>&1 | grep -E "Unknown (lvalue|key)" | head -10
        fail "$image: systemd logged an unknown directive while loading $NAME"
    fi

    docker exec "$CONTAINER" systemctl restart "$NAME" >/dev/null 2>&1 \
        || fail "$image: systemctl restart $NAME failed under this sandbox"
    local again=""
    for _ in $(seq 1 60); do
        again=$(curl -sf -m 5 "http://127.0.0.1:$port/health" 2>/dev/null) && break
        again=""
        sleep 2
    done
    [ -n "$again" ] || fail "$image: the unit did not come back after a restart"
    echo "   restart: healthy again"

    docker stop "$CONTAINER" >/dev/null 2>&1 || true
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    CONTAINER=""
    rm -f "$log"
}

# Each host gets its own port so a failed run cannot leave the next one arguing
# with a published socket.
offset=0
for image in $IMAGES; do
    check_host "$image" "$((PORT + offset))"
    offset=$((offset + 1))
done

echo
echo "systemd compat: the unit is fully understood on $IMAGES"
