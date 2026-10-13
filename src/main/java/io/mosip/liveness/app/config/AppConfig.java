package io.mosip.liveness.app.config;

import io.mosip.liveness.audit.AuditLogger;
import io.mosip.liveness.audit.MetricsCollector;
import io.mosip.liveness.audit.StructuredAuditLogger;
import io.mosip.liveness.backend.LivenessBackend;
import io.mosip.liveness.backend.LivenessBackendSelection;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.metrics.PipelineTimers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.shared.definitions.RepeatedFailureAction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.Set;
import java.time.Clock;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${mosip.liveness.passive-threshold:0.80}")
    private double passiveThreshold;

    @Value("${mosip.liveness.min-face-quality:0.50}")
    private double minFaceQuality;

    @Value("${mosip.liveness.passive-min-frames:5}")
    private int passiveMinFrames;

    @Value("${mosip.liveness.passive-window-frames:7}")
    private int passiveWindowFrames;

    @Value("${mosip.liveness.min-challenge-count:2}")
    private int minChallengeCount;

    @Value("${mosip.liveness.challenge-timeout-ms:15000}")
    private long challengeTimeoutMs;

    @Value("${mosip.liveness.max-retries:2}")
    private int maxRetries;

    private static volatile boolean openCvAvailable;
    private static final Object OPEN_CV_LOCK = new Object();
    /**
     * Volatile because the load now runs on a background thread: the fast path
     * in {@link #ensureOpenCvLoaded()} reads it outside the lock, and a plain
     * boolean would let a caller on the main thread observe a stale "not yet
     * attempted" and re-enter the lock pointlessly. Re-entering is only a
     * wasted monitor acquisition, but {@code openCvAvailable} must never be
     * reported before the attempt has settled.
     */
    private static volatile boolean openCvAttempted;
    private static volatile Thread openCvWarmupThread;
    /** Wall-clock cost of the native load, for the startup log line. */
    private static volatile long openCvLoadMs = -1;
    private static volatile Throwable openCvFailure;
    private static boolean openCvOutcomeReported;

    /**
     * Kicks off the native-library load on a background thread so the
     * context refresh never waits for it.
     *
     * <p>openpnp extracts a ~65 MB {@code libopencv_java490.so} into a temp
     * directory and {@code System.load}s it; that measured ~6.1 s. It used to
     * run serially in front of every other bean — first on the {@code main}
     * thread, then (after the async warm-up landed) blocking {@code init()}
     * and {@code PassiveScoringService}'s constructor, which still made every
     * boot pay the full price.</p>
     *
     * <p>Now nothing at boot waits: the load settles on this thread while the
     * context refreshes, and the <em>first frame submission</em> is what
     * actually needs the library — {@link #ensureOpenCvLoaded()} blocks only
     * if the background attempt has not settled by then, which is the rare
     * case (the load takes seconds; a boot that reaches a request takes
     * longer). {@code PassiveScoringService} likewise defers the MiniFASNet
     * model to first use, so no bean's creation depends on any of this.</p>
     *
     * <p>{@link #isOpenCvAvailable()} is therefore eventually consistent at
     * boot: a request in the first seconds after startup can read {@code false}
     * while the load is still in flight. The only consumer at rest is
     * {@code /health}, which reports the engine as unavailable until the
     * warm settles — accurate, and what the smoke test waits for.</p>
     *
     * <p>Idempotent, and safe to call from anywhere, including before the
     * context exists: {@link PadLivenessApplication} calls it first thing so the
     * warm covers the whole of {@code SpringApplication.run}, while
     * {@link #init()} repeats it for any embedding that starts the context
     * directly.</p>
     */
    public static void warmOpenCvAsync() {
        Thread existing = openCvWarmupThread;
        if (existing != null) {
            return;
        }
        synchronized (OPEN_CV_LOCK) {
            if (openCvWarmupThread != null) {
                return;
            }
            // This thread deliberately never logs. Logback drops events
            // emitted before Spring Boot initialises its logging system, and
            // this thread starts before SpringApplication.run() — on a warm
            // cache the load can settle inside the JVM's own fat-jar
            // class-loading time, before run() even begins, so an outcome
            // line logged here would vanish from packaged builds. The
            // outcome is therefore reported from the main thread instead:
            // by {@link #init()} once beans are being configured, or by
            // {@link #ensureOpenCvLoaded()} on the first frame.
            Thread warmup = new Thread(() -> loadOpenCvOnce(), "opencv-warmup");
            // Daemon: a slow or stuck extraction must never hold up JVM exit.
            warmup.setDaemon(true);
            openCvWarmupThread = warmup;
            warmup.start();
        }
    }

    /**
     * Never blocks on OpenCV. Boot pays nothing for the native library:
     * the warm-up thread settles the load in the background and the first
     * frame submission triggers it if that has not happened yet.
     */
    @PostConstruct
    void init() {
        warmOpenCvAsync();
        reportOutcomeWhenSettled();
    }

    /**
     * Reports the warm-up outcome from the main thread, the earliest
     * point at which logging is guaranteed to be live: Spring Boot has
     * attached logback's appenders by the time it configures beans,
     * whereas anything emitted from the warm-up thread can land before
     * that and be silently dropped (verified: the same {@code log.info}
     * survives from {@code init()} onwards but vanishes from the warm-up
     * thread before it).
     *
     * <p>When the load has already settled — the common case, since a
     * warm-cache load finishes inside the JVM's own class-loading time
     * — the outcome line is emitted right here. When it has not, a
     * short-lived daemon joins the warm-up thread and reports the moment
     * it settles, so the line still appears in the boot log without
     * making any boot step wait for it.</p>
     */
    private static void reportOutcomeWhenSettled() {
        if (reportOpenCvOutcomeOnce()) {
            return;
        }
        Thread warmup = openCvWarmupThread;
        if (warmup == null) {
            return;
        }
        Thread reporter = new Thread(() -> {
            try {
                warmup.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            reportOpenCvOutcomeOnce();
        }, "opencv-outcome");
        // Daemon: a stuck extraction must never hold up JVM exit.
        reporter.setDaemon(true);
        reporter.start();
    }

    /**
     * Loads the OpenCV native library <b>exactly once per JVM</b>, no matter
     * which bean asks first, and returns only once the attempt has settled.
     *
     * <p>Safe to call concurrently from several threads: whoever gets there
     * first performs the load while the rest block on the monitor, so this is
     * the synchronous barrier the first consumer waits on — the warm-up thread
     * calls {@link #loadOpenCvOnce()} directly, everything else goes through
     * here.</p>
     *
     * <p>This is the lazy trigger (first frame submission, first score): no
     * bean's creation depends on it, so bean instantiation order is irrelevant.
     * The first caller that genuinely needs the library — the frame pipeline,
     * {@code PassiveScoringService} resolving its scorer — performs the load,
     * everyone else gets the flag. Without the guard, that first caller could
     * race an in-flight warm-up and hit an {@code UnsatisfiedLinkError} on
     * {@code CascadeClassifier_1(String)} and silently degrade.</p>
     *
     * <p>openpnp extracts the native binary into a per-JVM temp directory and
     * deletes stale directories on startup; that extraction can transiently
     * race with another JVM (or a leftover directory) and fail. A failure
     * surfaces as an Error (UnsatisfiedLinkError / ExceptionInInitializerError),
     * not an Exception, so catching Exception alone would abort application
     * startup. Degrade instead, and report the state through /health.</p>
     *
     * @return true when frame processing is usable
     */
    public static boolean ensureOpenCvLoaded() {
        boolean result = loadOpenCvOnce();
        reportOpenCvOutcomeOnce();
        return result;
    }

    /**
     * Performs the load exactly once per JVM. Returns without logging so it is
     * safe to call from the warm-up thread, before logging is configured.
     */
    private static boolean loadOpenCvOnce() {
        if (openCvAttempted) {
            return openCvAvailable;
        }
        synchronized (OPEN_CV_LOCK) {
            if (openCvAttempted) {
                return openCvAvailable;
            }
            long began = System.currentTimeMillis();
            try {
                nu.pattern.OpenCV.loadLocally();
                openCvAvailable = true;
            } catch (Throwable t) {
                openCvAvailable = false;
                openCvFailure = t;
            }
            openCvLoadMs = System.currentTimeMillis() - began;
            openCvAttempted = true;
        }
        return openCvAvailable;
    }

    /**
     * Emits the load outcome exactly once, from a caller whose logging
     * is live ({@link #reportOutcomeWhenSettled()} on the main thread,
     * or {@link #ensureOpenCvLoaded()} on the first frame).
     *
     * <p>Returns {@code false} without consuming the one-shot flag when
     * the load attempt has not settled: the outcome is not knowable yet,
     * and claiming failure here would log a spurious warning and then
     * suppress the real outcome line when the attempt finally settles.
     * The {@code init()} caller relies on that to defer to the
     * join-watcher instead.</p>
     *
     * @return true when an outcome was emitted, false when the attempt
     *         is still in flight (or the outcome was already reported)
     */
    private static boolean reportOpenCvOutcomeOnce() {
        synchronized (OPEN_CV_LOCK) {
            if (openCvOutcomeReported || !openCvAttempted) {
                return false;
            }
            openCvOutcomeReported = true;
        }
        if (openCvAvailable) {
            log.info("OpenCV native library loaded successfully ({} ms)", openCvLoadMs);
        } else {
            log.warn("OpenCV native library could not be loaded; frame-processing "
                    + "endpoints will return 503 until it is available", openCvFailure);
        }
        return true;
    }

    /** True when the OpenCV native library loaded and frame processing is usable. */
    public static boolean isOpenCvAvailable() {
        return openCvAvailable;
    }

    /**
     * Backing store for the per-frame pipeline timers.
     *
     * <p>A plain {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry}
     * rather than the actuator one: this service exposes its own
     * {@code /api/v1/metrics}, so the registry only has to be a place to
     * accumulate. Declaring it here (instead of letting the static default in
     * {@code PipelineTimers} stand) means the registry is a Spring bean, so
     * swapping in a Prometheus-backed one later is a one-line change.</p>
     */
    @Bean
    public MeterRegistry meterRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PipelineTimers.use(registry);
        return registry;
    }

    @Bean
    public LivenessConfig livenessConfig() {
        Set<ChallengeType> challenges = EnumSet.of(
                ChallengeType.BLINK,
                ChallengeType.SMILE,
                ChallengeType.TURN_HEAD_LEFT,
                ChallengeType.TURN_HEAD_RIGHT
        );
        return LivenessConfig.builder()
                .passiveThreshold(passiveThreshold)
                .minFaceQuality(minFaceQuality)
                .passiveMinFrames(passiveMinFrames)
                .passiveWindowFrames(passiveWindowFrames)
                .minChallengeCount(minChallengeCount)
                .challengeTimeoutMs(challengeTimeoutMs)
                .maxRetries(maxRetries)
                .supportedChallengeTypes(challenges)
                .onRepeatedFailure(RepeatedFailureAction.LOCK_OUT)
                .build();
    }

    @Bean
    public LivenessBackend livenessBackend(@Value("${mosip.liveness.backend:auto}") String backend) {
        // The SAME key the HTTP scoring path reads (interop report F5): one
        // selection, both wirings, so they cannot silently diverge. In
        // 'auto'/'heuristic' this yields the scripted mock — this path's
        // behaviour before the key applied, preserved. Construction only:
        // FaceLivenessEngine calls initialize() itself, so selecting a model
        // backend loads nothing at boot.
        return LivenessBackendSelection.parse(backend).createBackend();
    }

    @Bean
    public AuditLogger auditLogger() {
        return StructuredAuditLogger.toStdout();
    }

    @Bean
    public MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    /**
     * The rate limiter's time source. Real time in production; tests replace it
     * with a movable clock to exercise window rollover without sleeping out a
     * 60-second window.
     */
    @Bean
    public Clock rateLimitClock() {
        return Clock.systemUTC();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // allowedOriginPatterns (not allowedOrigins) because only
                // patterns support wildcard ports — allowedOrigins("http://localhost:*")
                // never matches any real origin, so cross-origin dev servers were
                // silently rejected. Credentials stay off: nothing here uses cookies.
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                        // X-Subject-Ref is the ownership claim for a session-scoped
                        // call (UserSessionGuard); a browser client that creates a
                        // session with a subjectRef must be able to send it.
                        // X-Operator-API-Key is the smaller role's key, checked by
                        // OperatorKeyFilter on the operator routes; without it here a
                        // browser console could not present it at all.
                        .allowedHeaders("Content-Type", "Authorization", "X-Admin-API-Key",
                                "X-Subject-Ref", "X-Operator-API-Key")
                        // So a console on another dev port can read the limiter's
                        // budget headers (a browser hides unlisted headers).
                        .exposedHeaders("Retry-After", "X-RateLimit-Bucket", "X-RateLimit-Limit",
                                "X-RateLimit-Remaining", "X-RateLimit-Reset")
                        .allowCredentials(false);
            }
        };
    }
}
