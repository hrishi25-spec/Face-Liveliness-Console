package io.mosip.liveness.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Response for a single audit log entry.
 * Maps to the Python framework's audit trail response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntry {

    private UUID id;
    private String eventType;
    /**
     * The workflow this entry concerns, for entries that have one. Mirrors the
     * same value inside {@code details.workflowType}, but as a first-class field
     * so a client can filter on it without parsing the payload.
     */
    private String workflowType;
    private Map<String, Object> details;
    private String createdAt;
}
