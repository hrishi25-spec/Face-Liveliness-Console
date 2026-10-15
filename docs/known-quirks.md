# Known Quirks in MOSIP Liveness Service

This document characterizes behaviors that may resemble bugs but are intentional aspects of the current implementation. These are documented to prevent confusion during refactoring and to clarify expected behavior.

## Challenge Processing Quirks

### Challenge Status After Exhausted Retries
When a workflow is configured with `maxRetries=0` and a challenge fails, the frame processing status remains `CHALLENGE_IN_PROGRESS` rather than transitioning to a terminal state like `FAILED` or triggering the `onRepeatedFailure` action immediately.

**Observed in**: `RepeatedFailureActionsCharacterizationTest`
**Current Behavior**: Frame assessment returns `AssessmentStatus.CHALLENGE_IN_PROGRESS` even after challenge failure with no retries remaining.
**Note**: This differs from the expected behavior where exhausted retries should trigger the repeated failure action.

## PAD Detection Quirks

### Session State After PAD Detection
Once PAD is detected and returns `AssessmentStatus.PAD_BLOCKED`, attempting to process additional frames in the same session results in an exception rather than a graceful handling.

**Observed in**: `PadConfirmationCharacterizationTest`
**Current Behavior**: 
- First frame with PAD attack correctly returns `PAD_BLOCKED`
- Subsequent frame processing throws an exception (session state is considered ended)
**Note**: The session cannot be reused after PAD detection; a new session must be initialized.

## Workflow Policy Quirks

### Null Workflow Defaults
When `WorkflowPolicyDefaults.forWorkflow(null)` is called, it defaults to the RESIDENT_REGISTRATION workflow policies rather than returning null or throwing an exception.

**Observed in**: `WorkflowPolicyDefaultsCharacterizationTest`
**Current Behavior**: Null workflow input produces RESIDENT_REGISTRATION policy values
**Note**: This provides a fallback behavior but may mask configuration errors.
