#!/usr/bin/env bash
#
# Secret-scan gate: fails when a credential, private key or real biometric
# identifier is present in the tree or its history.
#
# The repository rule is that credentials, private keys, real biometric data and
# production identifiers never enter source, tests, logs or documentation — a
# rule nothing enforced until now. Review cannot be the enforcement: a
# `.env`-shaped value that nobody looked at twice is exactly what a scan is for,
# and `ApiRouteInventoryTest` (which classifies routes), the schema gate and the
# jar-size ceiling all guard their own surface the same way, from CI.
#
# `gitleaks git` reads the committed history rather than the working tree, which
# is what CI checks out and what makes the gate worth having: a secret that was
# committed and then removed still fails until the history is rewritten. (Run it
# before committing and it will not see the file you have not committed yet —
# that is the one case where the gate on its own is not enough.) `--redact` keeps
# a finding from being echoed into CI logs or a terminal scrollback: the report
# names the file, line and rule, never the value.
#
# Run it locally:  ./scripts/check-secrets.sh
# It uses the gitleaks CLI when one is on PATH (faster, no image pull) and the
# pinned container image otherwise, so the version is the same everywhere.
set -euo pipefail

cd "$(dirname "$0")/.."

# Pinned so a new gitleaks release cannot change what this gate accepts on its
# own schedule. Bump deliberately, and re-run it against the history when you do.
IMAGE="${GITLEAKS_IMAGE:-zricethezav/gitleaks:v8.30.1}"

if command -v gitleaks >/dev/null 2>&1; then
  exec gitleaks git --no-banner --redact "$@"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "check-secrets: neither the gitleaks CLI nor docker is available." >&2
  echo "Install gitleaks (or docker) to run this gate; it cannot be skipped silently." >&2
  exit 1
fi

exec docker run --rm -v "$PWD:/repo" -w /repo "$IMAGE" git --no-banner --redact "$@"
