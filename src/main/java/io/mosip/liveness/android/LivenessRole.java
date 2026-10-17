package io.mosip.liveness.android;

/**
 * The user-facing role (orchestration spec §2/§6). Mapped onto the shared
 * engine's {@link io.mosip.liveness.core.WorkflowType} so Android policy and
 * Desktop policy resolve from the same table.
 */
public enum LivenessRole {
    RESIDENT,
    OPERATOR,
    SUPERVISOR
}
