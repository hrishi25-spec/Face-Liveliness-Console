package io.mosip.liveness.models.entity;

import io.mosip.liveness.models.converter.StringObjectMapJsonConverter;
import io.mosip.liveness.models.enums.WorkflowType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single structured audit-trail entry, as stored in {@code audit_logs}.
 *
 * <p>{@code details} is persisted as JSON text through an explicit converter so
 * the mapping behaves identically on PostgreSQL and on H2 (the {@code dev}
 * profile).</p>
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * The session this entry belongs to.
     *
     * <p>{@code null} means an operator-level event with no session — currently
     * only {@code CONFIG_CHANGED}, which records a runtime policy edit. Pipeline
     * events always carry their session.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private LivenessSession session;

    /**
     * The workflow this entry concerns, denormalised so the audit feed can be
     * filtered in the database.
     *
     * <p>Previously recoverable only by parsing {@code details}. That made
     * "show me this workflow's policy changes" impossible to express as a
     * query: every row had to be returned, parsed and discarded. For config
     * edits the value also lives in the JSON, and it stays there — this column
     * is for indexing, not a replacement.</p>
     *
     * <p>Nullable: entries that predate the column, or that concern no single
     * workflow, stay {@code null} rather than being guessed at.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type", length = 32)
    private WorkflowType workflowType;

    /** Machine-readable event name, e.g. {@code SESSION_PASSED}, {@code PAD_REJECTED}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Convert(converter = StringObjectMapJsonConverter.class)
    @Column(name = "details", nullable = false, columnDefinition = "text")
    @Builder.Default
    private Map<String, Object> details = new HashMap<>();

    /**
     * The previous chained entry's {@code entry_hash}; {@link AuditChain#GENESIS}
     * for the first. Null on entries that are not part of a chain.
     */
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    /**
     * {@code SHA-256(prev_hash ‖ canonical(entry))}, computed by
     * {@link AuditChain}. Null on entries that are not part of a chain.
     */
    @Column(name = "entry_hash", length = 64)
    private String entryHash;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
