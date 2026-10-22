#!/usr/bin/env bash
#
# Build the Linux installers for the service:
#
#   target/dist/pad-liveness_<version>-1_amd64.deb    deb, systemd unit included
#   target/dist/pad-liveness-<version>-x86_64.AppImage  AppRun starts it and opens the console
#
# This is what the Maven `linux-package` profile runs, so a plain
#
#   ./mvnw package
#
# produces both on Linux. Run it by hand to debug one half:
#
#   ./scripts/package-linux.sh --skip-deb          # AppImage only
#   ./scripts/package-linux.sh --skip-appimage     # .deb only
#
# It is a script rather than Maven plugin configuration because jpackage is a
# CLI that wants an application image, and the AppImage step (appimagetool) has
# no Maven equivalent.
#
# Requirements (Linux x86_64):
#   * jpackage, from the JDK 17 the build already uses ($JAVA_HOME/bin/jpackage)
#   * dpkg-deb and fakeroot for the .deb — Debian/Ubuntu have both; on Arch,
#     `pacman -S dpkg` (fakeroot is in base-devel)
#   * appimagetool for the AppImage — fetched once from its pinned 1.9.1 release
#     and cached under ~/.cache/pad-liveness/tools. It also needs the `file`
#     command, which every Debian/Ubuntu image has but a minimal container does
#     not (without it appimagetool fails after it has already packed the image).
#
# Missing tools are a warning and that half is skipped, so a workstation without
# dpkg still gets the jar and the AppImage and CI without the fetcher still
# builds. `--strict` (the CI packaging job, and -Dlinux.package.strict=true)
# turns every skip into a failure instead.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

NAME="pad-liveness"
VERSION=""
JAR=""
TARGET_DIR="$ROOT/target"
DEST=""
BUILD_DEB=1
BUILD_APPIMAGE=1
STRICT="${PAD_PACKAGE_STRICT:-0}"
FETCH_TOOLS="${PAD_PACKAGE_FETCH:-1}"
ICON="$ROOT/packaging/linux/pad-liveness.png"
SERVICE="$ROOT/packaging/linux/pad-liveness.service"
APPRUN="$ROOT/packaging/linux/AppRun"
DESKTOP="$ROOT/packaging/linux/pad-liveness.desktop"
CACHE="${PAD_PACKAGE_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pad-liveness}"

# appimagetool 1.9.1, x86_64 AppImage. Pinned by version *and* digest: the
# `continuous` release tag moves, so a URL alone would let whatever is uploaded
# today become part of the artifact.
APPIMAGETOOL_VERSION="1.9.1"
APPIMAGETOOL_SHA256="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
APPIMAGETOOL_URL="https://github.com/AppImage/appimagetool/releases/download/${APPIMAGETOOL_VERSION}/appimagetool-x86_64.AppImage"

# Everything goes to stdout, including failures: exec-maven-plugin runs this
# script for `./mvnw package` and does not surface its stderr, so a warning on
# stderr would reach nobody in the build that matters most.
die()  { printf 'packaging: %s\n' "$*" >&2; printf 'packaging: %s\n' "$*"; exit 1; }
info() { printf '  %s\n' "$*"; }
warn() { printf 'packaging: WARNING: %s\n' "$*"; }
step() { printf '\n▸ %s\n' "$*"; }

# `skip` records a half that cannot be built here. Under --strict it is fatal,
# so CI cannot pass while silently shipping one artifact instead of two.
skipped=""
skip() {
    if [ "$STRICT" = 1 ]; then
        die "$* (strict mode: every requested installer must be built)"
    fi
    warn "$* — skipping"
    skipped="yes"
}

case "$STRICT" in
    1|0) ;;
    true|TRUE|yes) STRICT=1 ;;
    false|FALSE|no|"") STRICT=0 ;;
    *) die "PAD_PACKAGE_STRICT must be 0 or 1 (got '$STRICT')" ;;
esac

usage() {
    sed -n '4,12p' "$0" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Usage: scripts/package-linux.sh [options]

  --version <x.y.z>     package version (jpackage rejects -SNAPSHOT suffixes)
  --jar <path>          runnable jar; defaults to the slim jar when present,
                        otherwise the full one in --target
  --target <dir>        build directory (default: target)
  --dest <dir>          where the artifacts land (default: <target>/dist)
  --skip-deb            build only the AppImage
  --skip-appimage       build only the .deb
  --strict              a missing tool is an error, not a skipped artifact
  --no-fetch            never download appimagetool; use what is on PATH/cache
  -h, --help            this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version)        [ $# -ge 2 ] || die "--version needs a value"; VERSION="$2"; shift 2 ;;
        --jar)            [ $# -ge 2 ] || die "--jar needs a value"; JAR="$2"; shift 2 ;;
        --target)         [ $# -ge 2 ] || die "--target needs a value"; TARGET_DIR="$2"; shift 2 ;;
        --dest)           [ $# -ge 2 ] || die "--dest needs a value"; DEST="$2"; shift 2 ;;
        --skip-deb)       BUILD_DEB=0; shift ;;
        --skip-appimage)  BUILD_APPIMAGE=0; shift ;;
        --strict)         STRICT=1; shift ;;
        --no-fetch)       FETCH_TOOLS=0; shift ;;
        -h|--help)        usage; exit 0 ;;
        *)                die "unknown option: $1 (try --help)" ;;
    esac
done

[ "$(uname -s)" = "Linux" ] || die "only Linux is supported; jpackage cannot cross-build a .deb or AppImage from $(uname -s)"
case "$(uname -m)" in
    x86_64|amd64) ARCH=x86_64 ;;
    *) die "only x86_64 is wired up (found $(uname -m)); add an ARCH case before packaging elsewhere" ;;
esac

DEST=${DEST:-$TARGET_DIR/dist}
mkdir -p "$DEST"

# jpackage wants a numeric x.y.z and rejects the -SNAPSHOT suffix every local
# build carries. A tag build passes -Drevision=1.2.3, which passes through.
numeric_version() {
    local v="${1%%-*}"          # 1.0.0-SNAPSHOT -> 1.0.0
    case "$v" in
        [1-9]*.[0-9]*.[0-9]*) printf '%s' "$v" ;;
        [1-9]*.[0-9]*)        printf '%s.0' "$v" ;;
        *)                    printf '1.0.0' ;;
    esac
}
VERSION=$(numeric_version "${VERSION:-1.0.0}")

# ------------------------------------------------------------------ tool check

find_jpackage() {
    if [ -n "${JPACKAGE:-}" ] && [ -x "${JPACKAGE}" ]; then
        printf '%s' "$JPACKAGE"; return 0
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ]; then
        printf '%s' "$JAVA_HOME/bin/jpackage"; return 0
    fi
    command -v jpackage 2>/dev/null
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

find_appimagetool() {
    if [ -n "${APPIMAGETOOL:-}" ] && [ -x "${APPIMAGETOOL}" ]; then
        printf '%s' "$APPIMAGETOOL"; return 0
    fi
    if command -v appimagetool >/dev/null 2>&1; then
        command -v appimagetool; return 0
    fi
    local cached="$CACHE/tools/appimagetool-${APPIMAGETOOL_VERSION}-x86_64.AppImage"
    if [ -x "$cached" ]; then
        printf '%s' "$cached"; return 0
    fi
    [ "$FETCH_TOOLS" = 1 ] || return 1

    mkdir -p "$CACHE/tools"
    printf '  fetching appimagetool %s (%.0f MB, once per machine)\n' \
        "$APPIMAGETOOL_VERSION" 15 >&2
    local tmp="$cached.download"
    if ! curl -fsSL --max-time 300 "$APPIMAGETOOL_URL" -o "$tmp"; then
        rm -f "$tmp"
        return 1
    fi
    # A digest mismatch is fatal even outside --strict: the wrong bytes are not
    # a missing tool, and silently skipping would hide a tampered download.
    local actual
    actual=$(sha256_of "$tmp")
    if [ "$actual" != "$APPIMAGETOOL_SHA256" ]; then
        rm -f "$tmp"
        die "appimagetool sha256 mismatch: expected $APPIMAGETOOL_SHA256, got $actual"
    fi
    chmod 0755 "$tmp"
    mv "$tmp" "$cached"
    printf '%s' "$cached"
}

JPACKAGE=$(find_jpackage) || {
    # No jpackage means no app image at all, so neither installer can be built.
    skip "jpackage not found — it ships with JDK 17+; set JAVA_HOME or JPACKAGE"
    printf '\n▸ Nothing to package\n'
    exit 0
}
[ -f "$ICON" ] || die "icon missing: $ICON"

step "Preparing $NAME $VERSION"
info "jpackage: $JPACKAGE"

# -------------------------------------------------------------- runnable jar

if [ -z "$JAR" ]; then
    # The slim jar is the runnable one whenever the slim profile built it: that
    # build repackages with the `slim` classifier and leaves the *thin* jar
    # beside it, so preferring the plain jar there would package a jar with no
    # dependencies at all. A full build has no slim jar and no ambiguity.
    slim=$(find "$TARGET_DIR" -maxdepth 1 -name 'pad-liveness-backend-*-slim.jar' 2>/dev/null | sort | head -1 || true)
    full=$(find "$TARGET_DIR" -maxdepth 1 -name 'pad-liveness-backend-*.jar' ! -name '*-slim.jar' 2>/dev/null | sort | head -1 || true)
    JAR=${slim:-$full}
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "no runnable jar under $TARGET_DIR — run ./mvnw clean package -DskipTests first"
case "$JAR" in
    *-slim.jar) info "jar: $(basename "$JAR") (slim: linux-x86_64 natives only)" ;;
    *)          info "jar: $(basename "$JAR") (full: every platform's natives)" ;;
esac

# ------------------------------------------------------------------ app image
#
# --main-class must be JarLauncher: jpackage launches with `java -cp`, so it
# ignores the jar manifest's Start-Class and would otherwise run the Spring
# Boot loader's main class directly and never start Spring.

STAGE="$TARGET_DIR/jpackage/input"
APP_IMAGE="$TARGET_DIR/jpackage/$NAME"
# Only the halves being rebuilt are cleared: --skip-deb must not delete a .deb
# someone just built (and vice versa). `if`, not `[ ... ] && rm`: under set -e a
# false test would make that list the script's exit status.
rm -rf "$TARGET_DIR/jpackage"
if [ "$BUILD_DEB" = 1 ]; then rm -f "$DEST"/*.deb; fi
if [ "$BUILD_APPIMAGE" = 1 ]; then rm -f "$DEST"/*.AppImage; fi
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/app.jar"

step "Building the application image"
"$JPACKAGE" \
    --type app-image \
    --name "$NAME" \
    --app-version "$VERSION" \
    --input "$STAGE" \
    --main-jar app.jar \
    --main-class org.springframework.boot.loader.launch.JarLauncher \
    --dest "$TARGET_DIR/jpackage" \
    --icon "$ICON" \
    --vendor "MOSIP" \
    --description "MOSIP face liveness and PAD service with its browser console" \
    --java-options "-Djava.awt.headless=true"
[ -x "$APP_IMAGE/bin/$NAME" ] || die "jpackage did not produce $APP_IMAGE/bin/$NAME"
info "app-image: $APP_IMAGE ($(du -sh "$APP_IMAGE" | cut -f1))"

# ----------------------------------------------------------------------- .deb

if [ "$BUILD_DEB" = 1 ]; then
    if command -v dpkg-deb >/dev/null 2>&1 && command -v fakeroot >/dev/null 2>&1; then
        step "Building the .deb"
        # --app-image reuses the image above rather than rebuilding it.
        RAW_DEB_DIR="$TARGET_DIR/jpackage/deb"
        mkdir -p "$RAW_DEB_DIR"
        "$JPACKAGE" \
            --type deb \
            --app-image "$APP_IMAGE" \
            --name "$NAME" \
            --app-version "$VERSION" \
            --dest "$RAW_DEB_DIR" \
            --icon "$ICON" \
            --vendor "MOSIP" \
            --description "MOSIP face liveness and PAD service with its browser console" \
            --linux-package-name "$NAME" \
            --linux-menu-group "Utility" \
            --linux-app-category "utils" \
            --linux-deb-maintainer "MOSIP"
        RAW_DEB=$(ls "$RAW_DEB_DIR"/*.deb | head -1)

        # jpackage can only install what comes out of the app image, and the app
        # image lives under /opt — so the unit rides along as a payload file and
        # the package is repacked to put it where systemd looks. Extracting with
        # dpkg-deb -R keeps the maintainer scripts jpackage generated (the
        # desktop-entry registration) instead of replacing them.
        UNPACKED="$TARGET_DIR/jpackage/deb-work"
        rm -rf "$UNPACKED"
        dpkg-deb -R "$RAW_DEB" "$UNPACKED"
        install -D -m 0644 "$SERVICE" "$UNPACKED/lib/systemd/system/$NAME.service"

        # The menu entry jpackage generated lives under /opt and starts the
        # server with no window at all. Replace it with the same desktop entry
        # the AppImage uses (Terminal=true, so the log is visible) and point its
        # paths at the installed location.
        DEB_DESKTOP="$UNPACKED/opt/$NAME/lib/$NAME-$NAME.desktop"
        if [ -f "$DEB_DESKTOP" ]; then
            sed -e "s|^Exec=.*|Exec=/opt/$NAME/bin/$NAME|" \
                -e "s|^Icon=.*|Icon=/opt/$NAME/lib/$NAME.png|" \
                "$DESKTOP" > "$DEB_DESKTOP"
        fi

        # The hook goes *after the shebang*, not at the end: jpackage's
        # maintainer scripts finish with `exit 0`, so anything appended would
        # never run and the unit would appear installed but unloaded.
        daemon_reload_hook() {
            local script="$1"
            local hook='# Added by scripts/package-linux.sh: the unit files changed.'
            hook="$hook
systemctl daemon-reload >/dev/null 2>&1 || true"
            if [ -f "$script" ]; then
                head -1 "$script" > "$script.new"
                printf '%s\n' "$hook" >> "$script.new"
                tail -n +2 "$script" >> "$script.new"
                mv "$script.new" "$script"
            else
                printf '#!/bin/sh\nset -e\n%s\n' "$hook" > "$script"
            fi
            chmod 0755 "$script"
        }
        daemon_reload_hook "$UNPACKED/DEBIAN/postinst"
        daemon_reload_hook "$UNPACKED/DEBIAN/postrm"

        # jpackage's postinst runs `xdg-desktop-menu install` under `set -e`, and
        # xdg-desktop-menu exits 1 when no system menu directory is writable —
        # which is every server, container and CI runner, exactly the machines
        # this .deb targets. dpkg then leaves the package half-configured: the
        # files land in /opt, the postinst never finishes, and later upgrades
        # and the unit's first start inherit the mess. The menu entry is
        # cosmetic next to that, so a failure there must not decide the install.
        relax_desktop_menu() {
            local script="$1"
            [ -f "$script" ] || return 0
            # `#` as the delimiter: the replacement itself contains `|`.
            sed -i -E 's#^([[:space:]]*xdg-desktop-menu .*[^[:space:]])[[:space:]]*$#\1 || true#' "$script"
        }
        relax_desktop_menu "$UNPACKED/DEBIAN/postinst"
        relax_desktop_menu "$UNPACKED/DEBIAN/prerm"

        DEB="$DEST/$(basename "$RAW_DEB")"
        rm -f "$DEST"/*.deb
        dpkg-deb --build --root-owner-group "$UNPACKED" "$DEB" >/dev/null
        info ".deb: $DEB ($(du -h "$DEB" | cut -f1))"
        info "unit: /lib/systemd/system/$NAME.service"
    else
        skip "dpkg-deb and fakeroot are needed for the .deb — apt-get install dpkg fakeroot (Arch: pacman -S dpkg)"
    fi
fi

# ------------------------------------------------------------------- AppImage

if [ "$BUILD_APPIMAGE" = 1 ]; then
    if ! command -v file >/dev/null 2>&1; then
        skip "appimagetool needs the \`file\` command — apt-get install file"
    elif TOOL=$(find_appimagetool); then
        step "Building the AppImage"
        APPDIR="$TARGET_DIR/jpackage/AppDir"
        rm -rf "$APPDIR"
        mkdir -p "$APPDIR/usr/lib"
        cp -a "$APP_IMAGE" "$APPDIR/usr/lib/$NAME"
        install -m 0755 "$APPRUN" "$APPDIR/AppRun"
        install -m 0644 "$DESKTOP" "$APPDIR/$NAME.desktop"
        install -m 0644 "$ICON" "$APPDIR/$NAME.png"
        ln -sf "$NAME.png" "$APPDIR/.DirIcon"

        OUT="$DEST/$NAME-$VERSION-$ARCH.AppImage"
        # appimagetool is itself an AppImage: with FUSE available it just runs,
        # and on a build host without it `--appimage-extract-and-run` is the
        # documented fallback (it unpacks to a temp dir instead of mounting).
        if ! ARCH="$ARCH" "$TOOL" --no-appstream "$APPDIR" "$OUT"; then
            rm -f "$OUT"
            info "retrying appimagetool with --appimage-extract-and-run (no FUSE?)"
            ARCH="$ARCH" "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$OUT"
        fi
        [ -f "$OUT" ] || die "appimagetool did not produce $OUT"
        info "AppImage: $OUT ($(du -h "$OUT" | cut -f1))"
    else
        skip "appimagetool is needed for the AppImage — download it from https://github.com/AppImage/appimagetool/releases or set APPIMAGETOOL"
    fi
fi

# -------------------------------------------------------------------- summary

step "Done"
for artifact in "$DEST"/*.deb "$DEST"/*.AppImage; do
    [ -f "$artifact" ] || continue
    info "$(basename "$artifact")  $(du -h "$artifact" | cut -f1)"
done

if [ -n "$skipped" ]; then
    cat <<EOF

  Not every installer was built. Install the missing tools and re-run, or pass
  --strict to make this a failure.
EOF
    exit 0
fi

DEB_PATH=$(ls "$DEST"/*.deb 2>/dev/null | head -1 || true)
APPIMAGE_PATH=$(ls "$DEST"/*.AppImage 2>/dev/null | head -1 || true)
if [ -n "$DEB_PATH" ]; then
    cat <<EOF

  Install the .deb, then enable the bundled unit:

    sudo dpkg -i $DEB_PATH
    sudo systemctl daemon-reload
    sudo systemctl enable --now $NAME
EOF
fi
if [ -n "$APPIMAGE_PATH" ]; then
    cat <<EOF

  Run the AppImage (it opens http://127.0.0.1:8000/):

    $APPIMAGE_PATH
EOF
fi
