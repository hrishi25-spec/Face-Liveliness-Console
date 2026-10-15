package io.mosip.liveness.api;

import io.mosip.liveness.diagnostics.DiagnosticsService;
import io.mosip.liveness.diagnostics.DiagnosticsSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnostic mode's read side — the local-only debug panel's endpoint
 * (orchestration spec §10: raw scores, per-frame timings, FPS, delegate;
 * still no pixels).
 *
 * <p>Two independent gates, both fail-closed:
 * <ul>
 *   <li><b>Opt-in</b> — {@code mosip.liveness.diagnostics-enabled} must be
 *       true (default false);</li>
 *   <li><b>Local only</b> — the caller must resolve to loopback. The answer is
 *       computed from {@link ClientIpResolver}, so a trusted proxy's
 *       forwarded client is judged as the remote caller it is: tunnelling
 *       through a local reverse proxy does not make a remote client local.</li>
 * </ul>
 *
 * <p>Both failures return the same empty {@code 404} rather than a 403:
 * a remote caller (or anyone probing) learns nothing — not the mode's
 * existence, not its state. Responses are {@code no-store} so raw scores
 * never sit in a browser or proxy cache.
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private final DiagnosticsService diagnostics;
    private final ClientIpResolver ipResolver;

    @GetMapping
    public ResponseEntity<DiagnosticsSnapshot> diagnostics(HttpServletRequest request) {
        boolean local = ClientIpResolver.isLoopback(ipResolver.resolve(request));
        if (!diagnostics.isEnabled() || !local) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(diagnostics.snapshot());
    }
}
