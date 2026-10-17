package io.mosip.liveness.android;

/**
 * Persists {@code lockoutUntil} so a temporary lockout survives process death
 * (spec §8 {@code terminal()}, §12 "process death → lockoutUntil persisted in
 * DB"). The Android glue backs this with Room; tests use the in-memory impl.
 */
public interface LockoutStore {

    /** Epoch millis until which the gate must refuse to start. 0 = not locked. */
    long lockoutUntilEpochMs();

    void setLockoutUntilEpochMs(long epochMs);

    final class InMemory implements LockoutStore {
        private volatile long until;

        @Override
        public long lockoutUntilEpochMs() {
            return until;
        }

        @Override
        public void setLockoutUntilEpochMs(long epochMs) {
            this.until = epochMs;
        }
    }
}
