package io.mosip.liveness.models.enums;

/**
 * Workflow as exposed over the REST API and stored in the database.
 *
 * <p>Deliberately distinct from {@link io.mosip.liveness.core.WorkflowType}
 * (engine layer); {@code ConfigService} owns the conversion between the two.</p>
 */
public enum WorkflowType {
    RESIDENT,
    OPERATOR,
    SUPERVISOR
}
