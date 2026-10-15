"""The Python half of the cross-backend contract.

`tests/cross_backend_scenario.py` is the artifact the Spring project's
`CrossBackendContractTest` runs over a subprocess to compare the two services'
terminal verdicts. This module runs that same script — same command line, same
stdout JSON — so a change to it is caught here as well, in the job that has this
project's dependencies, rather than only in the cross-backend job.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]

# Mirrors what the Spring side's CrossBackendContractTest puts in the scenario's
# environment. The policy PUT is fail-closed, so the run needs a key present and
# set here rather than inherited from whatever shell started pytest.
ADMIN_KEY = "cross-backend-admin-key-0123456789abcdef"

# The documented terminal vocabulary (docs/configuration.md, mirrored by
# RepeatedFailureAction in the Spring service). The keys are the *policy*
# values, which are upper snake case in both backends (FailurePolicy here,
# models.enums.FailurePolicy there); the action and reason they map to are the
# lower-case, user-facing half of the same table.
TERMINAL_SHAPES = {
    "LOCK": ("locked", "max_retries_exceeded:locked_out", False),
    "ESCALATE": ("escalate_to_operator", "max_retries_exceeded:escalation_required", False),
    "ALLOW_RETRY": ("failed", "max_retries_exceeded", True),
}


def scenario(policy, max_retries=2):
    """Run the scenario script the way the Java-side test does."""
    result = subprocess.run(
        [
            sys.executable,
            "tests/cross_backend_scenario.py",
            "--policy",
            policy,
            "--max-retries",
            str(max_retries),
        ],
        cwd=BACKEND_ROOT,
        env={**os.environ, "MOSIP_ADMIN_API_KEY": ADMIN_KEY},
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest.mark.parametrize("policy", sorted(TERMINAL_SHAPES))
def test_scenario_reports_the_documented_terminal_shape(policy):
    action, failure_reason, may_retry_session = TERMINAL_SHAPES[policy]

    shape = scenario(policy)

    assert shape["policy"] == policy
    assert shape["action"] == action
    assert shape["failure_reason"] == failure_reason
    assert shape["may_retry_session"] is may_retry_session
    assert shape["session_status"] == "failed"
    # ChallengeStatus is upper snake case in both backends; SessionStatus is not.
    assert shape["challenge_status"] == "FAILED"
    assert shape["intermediate_actions"] == ["retry_challenge"], (
        "only the verdict that spends the last retry is terminal"
    )
    assert shape["challenges_issued"] == 2
    assert shape["message"], "the generic user-facing message is part of the shape"


def test_the_retry_budget_is_the_one_that_was_configured():
    shape = scenario("LOCK", max_retries=3)

    assert shape["challenges_issued"] == 3
    assert shape["intermediate_actions"] == ["retry_challenge", "retry_challenge"]
    assert shape["action"] == "locked", "the outcome does not depend on how big the budget was"
