package io.mosip.liveness.audit;

/** Sink for structured audit events. Implementations write to file/syslog/SIEM. */
public interface AuditLogger {

    void log(AuditEvent event);

    /** Discards events (tests, benchmarks). */
    static AuditLogger noop() {
        return event -> { };
    }
}
