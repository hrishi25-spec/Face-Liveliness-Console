package io.mosip.liveness.core;

/** Workflows that share identical liveness/PAD logic but may carry policy overrides. */
public enum WorkflowType {
    RESIDENT_REGISTRATION,
    OPERATOR_AUTH,
    SUPERVISOR_AUTH
}
