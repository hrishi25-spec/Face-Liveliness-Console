#!/usr/bin/env bash
#
# Stage, checksum and publish the files a release ships.
#
# The release job used to do all of this inline in three `run:` blocks, so the
# only way to find out whether the publish path worked was to push a tag at the
# real repository — the one operation with no undo. The same code now has two
# entry points:
#
#   ./scripts/release-assets.sh add --version 1.2.3 \
#       target/pad-liveness-backend-1.2.3.jar \
#       target/pad-liveness-backend-1.2.3-slim.jar \
#       target/dist/pad-liveness_1.2.3-1_amd64.deb \
#       target/dist/pad-liveness-1.2.3-1.x86_64.rpm \
#       target/dist/pad-liveness-1.2.3-x86_64.AppImage
#   ./scripts/release-assets.sh checksums
#   ./scripts/release-assets.sh attach --tag v1.2.3
#
# `attach` takes `--repo OWNER/NAME`, which is what makes the flow testable
# without touching this repository's releases: point it at a scratch repository
# you own and the same command creates a real release there. Add `--dry-run` to
# print the exact gh command and do nothing else (no token, no network).
#
#   # rehearse the whole publish against a scratch repo
#   ./scripts/release-assets.sh attach --tag v0.0.1-test --repo you/scratch
#
#   # show the command without running it
#   ./scripts/release-assets.sh attach --tag v1.2.3 --dry-run
#
# A `-canary` tag publishes as a prerelease, matching the release job: same
# assets, flagged so nobody mistakes it for the latest stable version.
#
# `rehearse` is the self-test: it builds a throwaway staging directory with
# release-shaped names, runs every guard (matching, missing, doubled, stale), and
# asserts the gh command lines for both the create and the upload path without
# contacting GitHub. CI runs it so this file cannot rot between tags.
#
# Exits non-zero on the first guard that fails, printing what was expected.
set -uo pipefail

DEST="release-assets"
COMMAND=""
VERSION=""
TAG=""
REPO=""
DRY_RUN=0
ASSUME="new"
FILES=()

die() { printf 'release-assets: %s\n' "$*" >&2; exit 1; }
note() { printf '  %s\n' "$*"; }

usage() {
    sed -n '4,31p' "$0" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Usage: scripts/release-assets.sh <command> [options]

  add --version <x.y.z> <file>...   copy release-named files into --dest
  checksums [--dest <dir>]          write and verify a .sha256 beside each binary
  attach --tag <tag> [options]      create or update the GitHub release
  rehearse                          run the built-in self-test (no network)

Options for attach:
  --repo <owner/name>   target another repository (a scratch repo, for a rehearsal)
  --dry-run             print the gh command instead of running it
  --assume new|existing with --dry-run, which path to print (default: new)
  --dest <dir>          staging directory (default: release-assets)
  -h, --help            this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        add|checksums|attach|rehearse) COMMAND="$1"; shift ;;
        --version) [ $# -ge 2 ] || die "--version needs a value"; VERSION="$2"; shift 2 ;;
        --tag)     [ $# -ge 2 ] || die "--tag needs a value";     TAG="$2"; shift 2 ;;
        --repo)    [ $# -ge 2 ] || die "--repo needs a value";    REPO="$2"; shift 2 ;;
        --dest)    [ $# -ge 2 ] || die "--dest needs a value";    DEST="$2"; shift 2 ;;
        --assume)  [ $# -ge 2 ] || die "--assume needs a value";  ASSUME="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) die "unknown option: $1 (try --help)" ;;
        *) FILES+=( "$1" ); shift ;;
    esac
done

[ -n "$COMMAND" ] || { usage; exit 2; }

# ------------------------------------------------------------------------ add
#
# Names are the contract: the release job asserts the stamped jar names and the
# .deb carries whatever Debian revision jpackage chose, so `add` accepts only
# the five release shapes for --version and refuses a second copy of a name.
# That is what stops a stale artifact from a previous build silently joining
# this release.
cmd_add() {
    [ -n "$VERSION" ] || die "add needs --version"
    [ "${#FILES[@]}" -gt 0 ] || die "add needs at least one file"
    mkdir -p "$DEST" || die "cannot create $DEST"
    for src in "${FILES[@]}"; do
        [ -f "$src" ] || die "no such file: $src"
        base=$(basename "$src")
        case "$base" in
            "pad-liveness-backend-$VERSION.jar"|"pad-liveness-backend-$VERSION-slim.jar") ;;
            "pad-liveness_$VERSION-"*.deb) ;;
            "pad-liveness-$VERSION-"*.rpm) ;;
            "pad-liveness-$VERSION-"*.AppImage) ;;
            *) die "$base is not a release asset name for version $VERSION" ;;
        esac
        [ -e "$DEST/$base" ] && die "$DEST/$base is already staged — a second copy would double-count"
        cp "$src" "$DEST/$base" || die "cannot copy $src to $DEST/$base"
        note "staged $DEST/$base"
    done
}

# ------------------------------------------------------------------ checksums
#
# One .sha256 per asset, named exactly like its file with the suffix appended —
# the convention `sha256sum -c <file>.sha256` verifies from the download
# directory with no path editing. The exact count is asserted first: five
# binaries is the release contract, and a sixth means something else got staged.
cmd_checksums() {
    [ -d "$DEST" ] || die "no staging directory: $DEST"
    cd "$DEST" || die "cannot enter $DEST"
    local binaries=() binary
    shopt -s nullglob
    binaries+=( pad-liveness-backend-*.jar )
    binaries+=( pad-liveness*.deb )
    binaries+=( pad-liveness*.rpm )
    binaries+=( pad-liveness*.AppImage )
    shopt -u nullglob
    if [ "${#binaries[@]}" -ne 5 ]; then
        ls -la
        die "expected two jars, one .deb, one .rpm and one AppImage in $DEST, found ${#binaries[@]}"
    fi
    for binary in "${binaries[@]}"; do
        sha256sum "$binary" > "$binary.sha256" || die "cannot checksum $binary"
        sha256sum -c "$binary.sha256" >/dev/null || die "checksum of $binary does not verify"
        note "checksummed $binary"
    done
}

# --------------------------------------------------------------------- attach
#
# `gh release create` fails when the release already exists, which is what a
# re-pushed tag looks like; `upload --clobber` replaces the assets in that case
# so a retried run converges instead of wedging. Keep that shape: the existence
# probe decides the branch, never the caller.
cmd_attach() {
    [ -n "$TAG" ] || die "attach needs --tag"
    [ -d "$DEST" ] || die "no staging directory: $DEST"
    cd "$DEST" || die "cannot enter $DEST"

    local assets=() asset
    shopt -s nullglob
    assets+=( pad-liveness-backend-*.jar )
    assets+=( pad-liveness-backend-*.jar.sha256 )
    assets+=( pad-liveness*.deb )
    assets+=( pad-liveness*.deb.sha256 )
    assets+=( pad-liveness*.rpm )
    assets+=( pad-liveness*.rpm.sha256 )
    assets+=( pad-liveness*.AppImage )
    assets+=( pad-liveness*.AppImage.sha256 )
    shopt -u nullglob
    # gh skips a missing file without failing, so a release with no slim jar or
    # no installer would publish cleanly and be noticed only by a downloader.
    if [ "${#assets[@]}" -ne 10 ]; then
        ls -la
        die "expected 10 release assets in $DEST, found ${#assets[@]}"
    fi
    for asset in "${assets[@]}"; do
        [ -f "$asset" ] || die "missing release asset: $asset"
    done

    VERSION="${TAG#v}"
    for jar in "pad-liveness-backend-$VERSION.jar" "pad-liveness-backend-$VERSION-slim.jar"; do
        [ -f "$jar" ] || die "expected a stamped jar named $jar"
        [ -f "$jar.sha256" ] || die "no checksum beside $jar"
    done

    local repo_args=() prerelease=()
    [ -n "$REPO" ] && repo_args=( --repo "$REPO" )
    case "$TAG" in *-canary*) prerelease=( --prerelease ) ;; esac

    local exists=0
    if [ "$DRY_RUN" = 1 ]; then
        case "$ASSUME" in
            new)      exists=0 ;;
            existing) exists=1 ;;
            *) die "--assume must be 'new' or 'existing' (got '$ASSUME')" ;;
        esac
        note "dry run: assuming the release $([ "$exists" = 1 ] && echo exists || echo does not exist)"
    elif gh release view "$TAG" "${repo_args[@]}" >/dev/null 2>&1; then
        exists=1
    fi

    # One place that knows how to run gh, so --dry-run cannot drift from what
    # the real path executes: it is the same argv, printed instead of spawned.
    run_gh() {
        if [ "$DRY_RUN" = 1 ]; then
            printf 'dry-run: gh %s\n' "$*"
            return 0
        fi
        gh "$@"
    }

    if [ "$exists" = 1 ]; then
        note "release $TAG exists — replacing the assets"
        run_gh release upload "$TAG" "${assets[@]}" --clobber "${repo_args[@]}" \
            || die "gh release upload failed"
    else
        # No --notes-start-tag: it errors when there is no previous release,
        # which would make the very first tag unpublishable.
        run_gh release create "$TAG" "${assets[@]}" \
            --title "$TAG" --generate-notes "${prerelease[@]}" "${repo_args[@]}" \
            || die "gh release create failed"
    fi
}

# ------------------------------------------------------------------- rehearse
#
# The self-test. Fixture files are enough because every rule here is about
# names, counts and argv — the release job runs the same commands on the real
# multi-hundred-megabyte artifacts. Each guard is exercised in the direction
# that must fail, not just the happy path.
cmd_rehearse() {
    local version="9.9.9"
    local work
    work=$(mktemp -d) || die "cannot create a temp directory"
    # `work` is local to this function, so the path is baked into the trap: by
    # the time EXIT runs the variable is out of scope (and set -u would fire).
    trap "rm -rf '$work'" EXIT

    # Absolute, because every case below runs the script from the fixture
    # directory rather than from wherever the caller happens to stand.
    local self
    self=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")
    local failures=0 out=""

    ok()   { note "ok   $*"; }
    fail() { printf '  FAIL %s\n' "$*" >&2; failures=$((failures + 1)); }
    expect_ok() {
        local label="$1"; shift
        if out=$("$@" 2>&1); then ok "$label"; else fail "$label"; printf '%s\n' "$out" >&2; fi
    }
    expect_fail() {
        local label="$1" pattern="$2"; shift 2
        if out=$("$@" 2>&1); then
            fail "$label (exit 0)"
        elif ! grep -q -- "$pattern" <<< "$out"; then
            fail "$label (message did not mention '$pattern')"
            printf '%s\n' "$out" >&2
        else
            ok "$label"
        fi
    }
    expect_contains() {
        local label="$1" needle="$2" haystack="$3"
        if grep -q -- "$needle" <<< "$haystack"; then ok "$label"; else
            fail "$label (missing '$needle')"; printf '%s\n' "$haystack" >&2
        fi
    }

    local src="$work/src" dest="$work/staged"
    local full="pad-liveness-backend-$version.jar"
    local slim="pad-liveness-backend-$version-slim.jar"
    local deb="pad-liveness_${version}-1_amd64.deb"
    local rpm="pad-liveness-$version-1.x86_64.rpm"
    local appimage="pad-liveness-$version-x86_64.AppImage"
    mkdir -p "$src/dist" "$dest" || die "cannot create the fixtures"
    : > "$src/$full"; : > "$src/$slim"
    : > "$src/dist/$deb"; : > "$src/dist/$rpm"; : > "$src/dist/$appimage"

    # A name that is not a release shape for this version must not be staged,
    # and the same name must not be staged twice.
    expect_fail "refuses a jar for another version" "not a release asset name" \
        "$self" add --dest "$work/other" --version 1.2.4 "$src/$full"
    expect_ok "stages the five binaries" \
        "$self" add --dest "$dest" --version "$version" \
        "$src/$full" "$src/$slim" "$src/dist/$deb" "$src/dist/$rpm" "$src/dist/$appimage"
    expect_fail "refuses a second copy of one name" "already staged" \
        "$self" add --dest "$dest" --version "$version" "$src/$full"
    expect_ok "writes and verifies five checksums" "$self" checksums --dest "$dest"

    local create_out
    create_out=$("$self" attach --dest "$dest" --tag "v$version" --repo example/scratch --dry-run 2>&1) \
        && ok "prints the create command for a new release" \
        || fail "prints the create command for a new release"
    expect_contains "create names the tag" "gh release create v$version" "$create_out"
    expect_contains "create targets the scratch repo" "--repo example/scratch" "$create_out"
    expect_contains "create generates notes" "--generate-notes" "$create_out"

    local upload_out
    upload_out=$("$self" attach --dest "$dest" --tag "v$version" --repo example/scratch \
        --dry-run --assume existing 2>&1) \
        && ok "prints the upload command for an existing release" \
        || fail "prints the upload command for an existing release"
    expect_contains "upload replaces assets" "gh release upload v$version" "$upload_out"
    expect_contains "upload clobbers" "--clobber" "$upload_out"
    expect_contains "upload targets the scratch repo" "--repo example/scratch" "$upload_out"

    # A canary tag is versioned too: the release job builds -Drevision=<tag
    # without the v>, so its assets carry the canary version in their names.
    local canary_version="$version-canary.1" csrc="$work/canary-src" cdest="$work/canary"
    local cfull="pad-liveness-backend-$canary_version.jar"
    local cslim="pad-liveness-backend-$canary_version-slim.jar"
    local cdeb="pad-liveness_${canary_version}-1_amd64.deb"
    local crpm="pad-liveness-$canary_version-1.x86_64.rpm"
    local cappimage="pad-liveness-$canary_version-x86_64.AppImage"
    mkdir -p "$csrc/dist" "$cdest"
    : > "$csrc/$cfull"; : > "$csrc/$cslim"
    : > "$csrc/dist/$cdeb"; : > "$csrc/dist/$crpm"; : > "$csrc/dist/$cappimage"
    "$self" add --dest "$cdest" --version "$canary_version" \
        "$csrc/$cfull" "$csrc/$cslim" "$csrc/dist/$cdeb" "$csrc/dist/$crpm" \
        "$csrc/dist/$cappimage" >/dev/null \
        && "$self" checksums --dest "$cdest" >/dev/null \
        || fail "stages a canary release"
    local canary_out
    canary_out=$("$self" attach --dest "$cdest" --tag "v$canary_version" --repo example/scratch --dry-run 2>&1)
    expect_contains "canary tag publishes as a prerelease" "--prerelease" "$canary_out"

    # The guards, in the direction that matters: a release missing an asset or
    # carrying a jar for another version must not be publishable.
    mv "$dest/$slim" "$work/hold-slim"
    expect_fail "refuses to publish with one asset missing" "expected 10 release assets" \
        "$self" attach --dest "$dest" --tag "v$version" --dry-run
    mv "$dest/$full" "$work/hold-full"
    expect_fail "refuses to checksum a short staging directory" "expected two jars" \
        "$self" checksums --dest "$dest"
    mv "$work/hold-full" "$dest/$full"
    mv "$work/hold-slim" "$dest/$slim"

    mv "$dest/$full" "$work/hold-full"
    cp "$dest/$slim" "$dest/pad-liveness-backend-1.2.4.jar"
    expect_fail "refuses a jar stamped with another version" "stamped jar" \
        "$self" attach --dest "$dest" --tag "v$version" --dry-run

    if [ "$failures" -ne 0 ]; then
        die "rehearsal failed: $failures check(s)"
    fi
    echo "rehearsal passed: staging, checksums and both gh paths"
}

case "$COMMAND" in
    add)       cmd_add ;;
    checksums) cmd_checksums ;;
    attach)    cmd_attach ;;
    rehearse)  cmd_rehearse ;;
esac
