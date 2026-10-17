package io.mosip.liveness.testing;

import io.mosip.liveness.audit.AuditEvent;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.audit.AuditLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test sink that records all events for assertions. */
public class RecordingAuditLogger implements AuditLogger {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void log(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public long count(AuditEventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    public boolean contains(AuditEventType type) {
        return count(type) > 0;
    }
}
