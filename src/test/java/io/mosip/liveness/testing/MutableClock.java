package io.mosip.liveness.testing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Clock advanced manually by tests to simulate challenge timeouts. */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(long epochMillis) {
        this.instant = Instant.ofEpochMilli(epochMillis);
    }

    public void advanceMillis(long ms) {
        instant = instant.plusMillis(ms);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return instant; }
}
