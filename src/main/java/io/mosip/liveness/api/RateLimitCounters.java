package io.mosip.liveness.api;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-rule tallies of what the rate limiter has seen, so operators can tell
 * whether it is biting (and which rule bites) without reading container logs.
 *
 * <p>Kept apart from {@link RateLimitFilter} on purpose: the filter is a servlet
 * {@code Filter}, and anything mocking it inside a {@code @WebMvcTest} slice is
 * auto-registered as a filter itself — swallowing every request before it
 * reaches a handler. A plain component also keeps {@code MetricsController}
 * independent of servlet plumbing.</p>
 *
 * <p>ponytail: counters are per-process and start at zero on restart, unlike the
 * session metrics in {@code /api/v1/metrics}, which are queried from the
 * database. A multi-node deployment would need a shared store (Redis /
 * caffeine) for the limiter itself, and these tallies with it.</p>
 */
@Component
public class RateLimitCounters {

    private final AtomicLong sessionCreateAllowed = new AtomicLong();
    private final AtomicLong sessionCreateRejected = new AtomicLong();
    private final AtomicLong framesAllowed = new AtomicLong();
    private final AtomicLong framesRejected = new AtomicLong();
    private final AtomicLong operatorKeyAllowed = new AtomicLong();
    private final AtomicLong operatorKeyRejected = new AtomicLong();

    /** A request on the per-IP session-creation budget was admitted or refused. */
    public void recordSessionCreate(boolean allowed) {
        bump(allowed ? sessionCreateAllowed : sessionCreateRejected);
    }

    /** A request on the per-session frame / challenge-validation budget. */
    public void recordFrames(boolean allowed) {
        bump(allowed ? framesAllowed : framesRejected);
    }

    /**
     * A request on the per-IP operator-key budget (an operator route). Its own
     * pair of tallies rather than the session-create ones: sharing them would
     * report a key guess as a refused session creation, and the whole point of
     * these counters is that an operator can see which rule is biting.
     */
    public void recordOperatorKey(boolean allowed) {
        bump(allowed ? operatorKeyAllowed : operatorKeyRejected);
    }

    private void bump(AtomicLong counter) {
        counter.incrementAndGet();
    }

    public Counts snapshot() {
        return new Counts(sessionCreateAllowed.get(), sessionCreateRejected.get(),
                framesAllowed.get(), framesRejected.get(),
                operatorKeyAllowed.get(), operatorKeyRejected.get());
    }

    /** An immutable read of the tallies, as reported by {@code /api/v1/metrics}. */
    public record Counts(long sessionCreateAllowed, long sessionCreateRejected,
                         long framesAllowed, long framesRejected,
                         long operatorKeyAllowed, long operatorKeyRejected) {

        public long allowed() {
            return sessionCreateAllowed + framesAllowed + operatorKeyAllowed;
        }

        public long rejected() {
            return sessionCreateRejected + framesRejected + operatorKeyRejected;
        }

        /** Share of limited-route requests that were refused; 0.0 when there were none. */
        public double rejectionRate() {
            long total = allowed() + rejected();
            return total == 0 ? 0.0 : (double) rejected() / total;
        }
    }
}