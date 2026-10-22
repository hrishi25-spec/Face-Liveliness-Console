#!/usr/bin/env bash
#
# Boot the Linux installers and prove they are installable artifacts, not just
# files that exist.
#
# `./mvnw package` producing target/dist/*.deb and target/dist/*.AppImage only
# says jpackage and appimagetool exited 0. The failures that reach a user are
# all silent at build time:
#
#   * the .deb installs the launcher somewhere the unit's ExecStart does not
#     point at (jpackage's --linux-package-name changing /opt/<name>),
#   * the systemd unit never makes it into the archive, or arrives without the
#     daemon-reload hook, so `systemctl start` reports "unit not found" after a
#     clean install,
#   * AppRun cannot find usr/lib/pad-liveness inside the image and exits before
#     the service starts — the AppImage looks fine in a file manager,
#   * the bundled jar lost its OpenCV natives, which only shows up as
#     `engine: unavailable` once the image boots.
#
# So this asserts the archive *contents* and then actually boots the AppImage,
# probing /health and the console page.
#
# The unit inside the .deb gets the same treatment: its sandboxing directives
# are checked against the *packaged* file, and with SMOKE_UNIT_BOOT=1 the
# package is installed and the service started, because a unit that passes
# `systemd-analyze verify` can still fail to mount its own sandbox at runtime.
#
# Needs dpkg-deb (Debian/Ubuntu have it) and curl. SMOKE_DIST, SMOKE_PORT and
# SMOKE_LOG override the defaults; SMOKE_UNIT_BOOT=1 needs root (or passwordless
# sudo, which is what CI has) and a free port 8000. Exits non-zero with the tail
# of the application log on any failure.
set -uo pipefail

NAME="pad-liveness"
DIST="${SMOKE_DIST:-target/dist}"
PORT="${SMOKE_PORT:-8093}"
LOG="${SMOKE_LOG:-/tmp/linux-packages-smoke.log}"
UNIT_BOOT="${SMOKE_UNIT_BOOT:-0}"
PACKAGE_INSTALLED=0
APP_PID=""
# Empty when already root, so the same script works on a CI runner (sudo
# without a password) and in a root container. Resolved in the boot section.
SUDO=""


fail() {
    echo "::error::$*"
    exit 1
}

command -v dpkg-deb >/dev/null 2>&1 \
    || fail "dpkg-deb is required to inspect the .deb — apt-get install dpkg"

shopt -s nullglob
DEBS=( "$DIST"/*.deb )
APPIMAGES=( "$DIST"/*.AppImage )
shopt -u nullglob

[ "${#DEBS[@]}" -gt 0 ] \
    || fail "no .deb in $DIST — build it: ./mvnw package -DskipTests"
[ "${#APPIMAGES[@]}" -gt 0 ] \
    || fail "no AppImage in $DIST — build it: ./mvnw package -DskipTests"
[ "${#DEBS[@]}" -eq 1 ] \
    || fail "expected one .deb in $DIST, found ${#DEBS[@]}: ${DEBS[*]}"
[ "${#APPIMAGES[@]}" -eq 1 ] \
    || fail "expected one AppImage in $DIST, found ${#APPIMAGES[@]}: ${APPIMAGES[*]}"

DEB="${DEBS[0]}"
APPIMAGE="${APPIMAGES[0]}"
echo "checking: $DEB"
echo "checking: $APPIMAGE"

# Both halves leave something behind if a later assertion fails: a running
# AppImage process and, with SMOKE_UNIT_BOOT=1, an installed package. One trap
# owns both, so a failure never needs a manual cleanup before the next run.
cleanup() {
    if [ -n "$APP_PID" ]; then
        kill -TERM -- "-$APP_PID" 2>/dev/null || true
        for _ in $(seq 1 10); do
            kill -0 "$APP_PID" 2>/dev/null || break
            sleep 1
        done
        kill -KILL -- "-$APP_PID" 2>/dev/null || true
    fi
    if [ "$PACKAGE_INSTALLED" = 1 ]; then
        $SUDO systemctl stop "$NAME" >/dev/null 2>&1 || true
        $SUDO dpkg --purge "$NAME" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# ------------------------------------------------------------------ .deb

[ "$(dpkg-deb -f "$DEB" Package)" = "$NAME" ] \
    || fail "$DEB does not declare Package: $NAME"
[ "$(dpkg-deb -f "$DEB" Architecture)" = "amd64" ] \
    || fail "$DEB is not built for amd64 (jpackage names it after the host dpkg architecture)"
dpkg-deb -f "$DEB" Version >/dev/null || fail "$DEB has no Version field"
echo "  package: $(dpkg-deb -f "$DEB" Package) $(dpkg-deb -f "$DEB" Version) ($(dpkg-deb -f "$DEB" Architecture))"

LISTING=$(dpkg-deb -c "$DEB") || fail "cannot list the contents of $DEB"

# The unit's ExecStart must name a file the archive actually installs, with the
# execute bit set: the launcher is what systemd runs.
UNIT_PATH="lib/systemd/system/$NAME.service"
printf '%s\n' "$LISTING" | grep -q "\./$UNIT_PATH\$" \
    || fail "$DEB does not install /$UNIT_PATH — the unit was not added to the payload"

UNIT=$(dpkg-deb --fsys-tarfile "$DEB" | tar -xO "./$UNIT_PATH") \
    || fail "cannot read /$UNIT_PATH out of $DEB"
EXEC_BINARY=$(printf '%s\n' "$UNIT" | sed -n 's/^ExecStart=\([^ ]*\).*/\1/p' | head -1)
[ -n "$EXEC_BINARY" ] || fail "/$UNIT_PATH has no ExecStart"
EXEC_IN_ARCHIVE=".${EXEC_BINARY}"
MODE=$(printf '%s\n' "$LISTING" | awk -v p="$EXEC_IN_ARCHIVE" '$NF==p {print $1; exit}')
case "$MODE" in
    -rwx*) ;;
    "") fail "cannot find $EXEC_BINARY in $DEB" ;;
    *)  fail "$EXEC_BINARY is installed as $MODE — not executable" ;;
esac
echo "  unit: $UNIT_PATH → $EXEC_BINARY ($MODE)"

# The daemon-reload hook is why the unit works immediately after install. It
# lives in a maintainer script, so the check is on the archive's control
# members, not on the payload.
if ! dpkg-deb -I "$DEB" | grep -q 'postinst'; then
    fail "$DEB has no postinst; the unit would need a manual daemon-reload"
fi
POSTINST=$(dpkg-deb --ctrl-tarfile "$DEB" | tar -xO ./postinst 2>/dev/null || true)
HOOK_LINE=$(printf '%s\n' "$POSTINST" | grep -n 'daemon-reload' | head -1 | cut -d: -f1)
[ -n "$HOOK_LINE" ] \
    || fail "$DEB's postinst does not run systemctl daemon-reload"
# jpackage's scripts end with `exit 0`. A hook appended after it is dead code,
# which reads as a correct package and only shows up as a unit systemd has
# never loaded — so assert the line runs before anything can exit.
FIRST_EXIT=$(printf '%s\n' "$POSTINST" | grep -n '^[[:space:]]*exit\b' | head -1 | cut -d: -f1)
if [ -n "$FIRST_EXIT" ] && [ "$HOOK_LINE" -gt "$FIRST_EXIT" ]; then
    fail "the daemon-reload hook in $DEB's postinst is after the first exit — it never runs"
fi
echo "  postinst: daemon-reload at line $HOOK_LINE (first exit: ${FIRST_EXIT:-none})"

# --------------------------------------------------------------- unit sandbox
#
# The unit is the only thing between the engine and the host once it runs as a
# daemon, and losing a directive is silent: the service still boots, just with
# more of the machine in reach. So the directives are asserted on the file the
# archive installs, not on packaging/linux/*.service — a unit that never made it
# into the payload is exactly the failure this would otherwise miss.
#
# Two directives are forbidden rather than required, because each one stops the
# service from starting at all: HotSpot compiles to pages it then executes, so
# MemoryDenyWriteExecute=yes aborts the JVM, and PrivateUsers=yes remaps the
# DynamicUser away from its own StateDirectory.
require_directive() {
    printf '%s\n' "$UNIT" | grep -qx -- "$1" \
        || fail "/$UNIT_PATH is missing '$1' — the installed service would run unhardened"
}
for directive in \
    DynamicUser=yes \
    StateDirectoryMode=0750 \
    UMask=0027 \
    NoNewPrivileges=yes \
    CapabilityBoundingSet= \
    AmbientCapabilities= \
    ProtectSystem=strict \
    ProtectHome=yes \
    PrivateTmp=yes \
    PrivateDevices=yes \
    ProtectKernelTunables=yes \
    ProtectKernelModules=yes \
    ProtectKernelLogs=yes \
    ProtectControlGroups=yes \
    ProtectClock=yes \
    ProtectHostname=yes \
    ProtectProc=invisible \
    ProcSubset=pid \
    RestrictNamespaces=yes \
    RestrictRealtime=yes \
    RestrictSUIDSGID=yes \
    LockPersonality=yes \
    RemoveIPC=yes \
    SystemCallArchitectures=native \
    SystemCallFilter=@system-service \
    SystemCallErrorNumber=EPERM
do
    require_directive "$directive"
done
# The family list is checked for contents, not order: AF_NETLINK is the one that
# looks unnecessary and is not — glibc opens it to choose a source address while
# resolving the database host, and dropping it breaks only `prod`.
ADDRESS_FAMILIES=$(printf '%s\n' "$UNIT" | sed -n 's/^RestrictAddressFamilies=//p' | head -1)
for family in AF_UNIX AF_INET AF_INET6 AF_NETLINK; do
    case " $ADDRESS_FAMILIES " in
        *" $family "*) ;;
        *) fail "/$UNIT_PATH's RestrictAddressFamilies does not allow $family (has '$ADDRESS_FAMILIES')" ;;
    esac
done
for forbidden in MemoryDenyWriteExecute=yes PrivateUsers=yes; do
    if printf '%s\n' "$UNIT" | grep -qx -- "$forbidden"; then
        fail "/$UNIT_PATH sets $forbidden, which stops the JVM from starting"
    fi
done
echo "  sandbox: 26 directives present, address families [$ADDRESS_FAMILIES]"

# ------------------------------------------------------------------ AppImage

[ -x "$APPIMAGE" ] || fail "$APPIMAGE is not executable"
echo "  AppImage: $(du -h "$APPIMAGE" | cut -f1)"

# Without FUSE (containers, some CI runners) the runtime cannot mount itself;
# --appimage-extract-and-run unpacks to a temp dir instead and runs the same
# AppRun, which is the documented fallback and what this gate exercises there.
RUN_ARGS=()
if [ ! -c /dev/fuse ] || ! command -v fusermount >/dev/null 2>&1; then
    echo "  no FUSE: using --appimage-extract-and-run"
    RUN_ARGS=( --appimage-extract-and-run )
fi

# setsid: the runtime, AppRun and the JVM are three processes. Signalling the
# group is the only way to be sure the JVM does not outlive the check and hold
# the port for the next run.
setsid env \
    PAD_LIVENESS_NO_BROWSER=1 \
    PAD_LIVENESS_PORT="$PORT" \
    "$APPIMAGE" "${RUN_ARGS[@]}" > "$LOG" 2>&1 &
APP_PID=$!

BODY=""
for _ in $(seq 1 100); do
    if BODY=$(curl -sf -m 5 "http://127.0.0.1:$PORT/health" 2>/dev/null); then
        break
    fi
    # A launcher that exits is the common failure (bad AppRun path); report it
    # immediately instead of waiting out the whole budget.
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "::error::the AppImage exited before /health responded"
        tail -40 "$LOG"
        exit 1
    fi
    sleep 2
done

[ -n "$BODY" ] || {
    echo "::error::the AppImage did not serve /health within 200s"
    tail -40 "$LOG"
    exit 1
}
echo "  health: $BODY"

# The same assertion the jar smoke test makes: a 200 alone would pass with the
# engine degraded, and the bundled jar is the whole point of the image.
case "$BODY" in
    *'"engine":"available"'*) ;;
    *)
        echo "::error::OpenCV natives missing or unloadable in the bundled jar"
        tail -40 "$LOG"
        exit 1
        ;;
esac

# The console is what AppRun opens — a redirect to a login page or an empty body
# would still be 200, so assert HTML actually comes back.
CONTENT_TYPE=$(curl -sf -m 10 -o /dev/null -w '%{content_type}' "http://127.0.0.1:$PORT/") || {
    echo "::error::the console page at / did not answer"
    tail -40 "$LOG"
    exit 1
}
case "$CONTENT_TYPE" in
    text/html*) ;;
    *) fail "the console at / served '$CONTENT_TYPE', expected text/html" ;;
esac
echo "  console: / served $CONTENT_TYPE"

echo "linux installers booted: $NAME .deb and AppImage"

# ------------------------------------------------------------- installed unit
#
# Contents checks cannot see whether the sandbox actually mounts. A missing user
# namespace, a StateDirectory the DynamicUser cannot own, or a ProcSubset the
# JVM needs are all runtime failures: `systemd-analyze verify` calls the unit
# well-formed and the service never answers. So with SMOKE_UNIT_BOOT=1 this
# installs the package the way its README tells a user to, starts the unit and
# probes what they would reach. The trap stops and purges it again.
#
# Opt-in because it needs root and port 8000; CI passes the variable from an
# ordinary runner, so the gate is the same one a maintainer can run by hand.
if [ "$UNIT_BOOT" = 1 ]; then
    echo
    echo "booting the packaged unit"
    if [ "$(id -u)" -eq 0 ]; then
        SUDO=""
    elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
        SUDO="sudo"
    else
        fail "SMOKE_UNIT_BOOT=1 installs a package and starts a systemd unit — run it as root or with passwordless sudo"
    fi
    unit_log() { $SUDO journalctl -u "$NAME" --no-pager 2>/dev/null | tail -40; }

    # The unit hardcodes 0.0.0.0:8000. Finding that occupied after installing is
    # a confusing Spring stack trace, so check before touching the package.
    if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q '127.0.0.1:8000\|0.0.0.0:8000\|\*:8000'; then
        fail "port 8000 is already in use — the unit binds it, so this gate needs it free"
    fi

    echo "  installing $(basename "$DEB")"
    # Set before the install, not after: a failed dpkg leaves a half-configured
    # package behind, and the trap is the only thing that removes it.
    PACKAGE_INSTALLED=1
    $SUDO dpkg -i "$DEB" > /tmp/linux-packages-dpkg.log 2>&1 \
        || { tail -20 /tmp/linux-packages-dpkg.log; fail "dpkg -i $DEB failed"; }
    $SUDO systemctl daemon-reload || fail "systemctl daemon-reload failed after installing $DEB"

    # The README promises the unit is installed but not enabled: binding port
    # 8000 is the installer's decision. An enabled unit would start on the next
    # boot of a machine that only wanted the console.
    ENABLED=$($SUDO systemctl is-enabled "$NAME" 2>/dev/null || true)
    [ "$ENABLED" != "enabled" ] \
        || fail "the package enabled $NAME; it must be installed and left disabled"

    $SUDO systemctl start "$NAME" || { unit_log; fail "systemctl start $NAME failed"; }

    UNIT_BODY=""
    for _ in $(seq 1 60); do
        if UNIT_BODY=$(curl -sf -m 5 http://127.0.0.1:8000/health 2>/dev/null); then break; fi
        if ! $SUDO systemctl is-active --quiet "$NAME"; then
            unit_log
            fail "the installed unit exited before /health answered (active=$($SUDO systemctl is-active "$NAME" 2>/dev/null))"
        fi
        sleep 2
    done
    [ -n "$UNIT_BODY" ] || { unit_log; fail "the installed unit did not serve /health within 120s"; }
    case "$UNIT_BODY" in
        *'"engine":"available"'*) ;;
        *) unit_log; fail "the installed unit reports the engine unavailable — the sandbox is blocking a native it needs" ;;
    esac
    echo "  unit health: $UNIT_BODY"

    UNIT_CT=$(curl -sf -m 10 -o /dev/null -w '%{content_type}' http://127.0.0.1:8000/) \
        || { unit_log; fail "the console page at / did not answer under the installed unit"; }
    case "$UNIT_CT" in
        text/html*) ;;
        *) fail "the installed unit's console served '$UNIT_CT', expected text/html" ;;
    esac
    echo "  unit console: / served $UNIT_CT"

    # DynamicUser is the directive that decides whether the StateDirectory is
    # usable at all; if systemd silently ignored it the service would be running
    # as root, which is the opposite of the intent.
    [ "$($SUDO systemctl show "$NAME" -p DynamicUser --value)" = "yes" ] \
        || fail "$NAME is not running under DynamicUser — check the unit's user settings"
    [ -d "/var/lib/$NAME" ] \
        || fail "/var/lib/$NAME was not created from StateDirectory — the service has nowhere to write"
    echo "  unit user: DynamicUser, state dir /var/lib/$NAME"

    # Restart=on-failure with a sandbox that only works on the first start would
    # pass everything above and fail in the field on the first crash.
    $SUDO systemctl restart "$NAME" || { unit_log; fail "systemctl restart $NAME failed"; }
    RESTARTED=""
    for _ in $(seq 1 60); do
        if RESTARTED=$(curl -sf -m 5 http://127.0.0.1:8000/health 2>/dev/null); then break; fi
        sleep 2
    done
    [ -n "$RESTARTED" ] || { unit_log; fail "the installed unit did not come back after a restart"; }
    echo "  unit restart: healthy again"

    $SUDO systemctl stop "$NAME" || true
fi
