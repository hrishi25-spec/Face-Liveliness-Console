#!/usr/bin/env bash
# Fail the build when a packaged jar grows past its budget.
#
# Nothing else catches this. The two jars carry roughly 300 MB of platform
# natives between them, and a dependency bump that quietly re-adds a platform
# nobody asked for breaks no test: the build stays green, every artifact
# download gets slower, and the only symptom is artifact storage quietly
# doubling. A ceiling turns that into a red build on the run that caused it.
#
# Budgets are set ~20% above today's jars on purpose — enough headroom that a
# legitimate patch release does not trip it, tight enough that a whole extra
# platform (~50-100 MB) does.
#
# usage: scripts/check-jar-size.sh <jar-or-glob> <max-mib> [label]
# Exits non-zero when the jar is missing, ambiguous or over budget.
set -uo pipefail

PATTERN="${1:-}"
MAX_MIB="${2:-}"
LABEL="${3:-$(basename "${PATTERN:-jar}")}"

if [ -z "$PATTERN" ] || [ -z "$MAX_MIB" ]; then
  echo "usage: $0 <jar-or-glob> <max-mib> [label]" >&2
  exit 2
fi
if ! [[ "$MAX_MIB" =~ ^[0-9]+$ ]]; then
  echo "::error::budget must be a whole number of MiB, got '$MAX_MIB'" >&2
  exit 2
fi

# Word splitting is deliberate: the argument is usually a glob, and a silent
# literal-that-does-not-exist would turn this check into a no-op.
shopt -s nullglob
# shellcheck disable=SC2206
FILES=( $PATTERN )
shopt -u nullglob

case "${#FILES[@]}" in
  0)
    echo "::error::$LABEL: no jar matched '$PATTERN' — nothing was checked"
    exit 1
    ;;
  1) ;;
  *)
    echo "::error::$LABEL: '$PATTERN' matched ${#FILES[@]} files; the budget needs one jar"
    printf '         %s\n' "${FILES[@]}"
    exit 1
    ;;
esac

JAR="${FILES[0]}"
BYTES=$(wc -c < "$JAR")
MAX_BYTES=$(( MAX_MIB * 1024 * 1024 ))

if [ "$BYTES" -gt "$MAX_BYTES" ]; then
  echo "::error::$LABEL grew past its budget: $(( BYTES / 1048576 )) MiB > ${MAX_MIB} MiB"
  echo "         a native bundle or debug payload came back — check the last dependency change"
  exit 1
fi

echo "$LABEL: $(( BYTES / 1048576 )) MiB / ${MAX_MIB} MiB budget"