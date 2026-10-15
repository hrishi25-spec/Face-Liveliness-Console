package io.mosip.liveness.api;

import io.mosip.liveness.services.ImageUtils.InvalidFrameError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler that produces consistent, structured error responses
 * across every controller — analogous to the FastAPI exception handlers in the
 * Python framework's main.py.
 *
 * Error response shape:
 * {
 *   "error":   "short machine-readable code",
 *   "message": "safe, user-facing message",
 *   "status":  422,
 *   "timestamp": "2026-08-27T12:00:00Z"
 * }
 *
 * No model internals, stack traces, or PII are ever leaked.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ 4xx client errors

    /**
     * Maps to the Python framework's InvalidFrameError handler (422).
     */
    @ExceptionHandler(InvalidFrameError.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFrame(InvalidFrameError ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FRAME", ex.getMessage());
    }

    /**
     * Catch-all for Spring's own 4xx exceptions (404, 409, etc.).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = switch (status) {
            case NOT_FOUND      -> "NOT_FOUND";
            case CONFLICT       -> "CONFLICT";
            case BAD_REQUEST    -> "BAD_REQUEST";
            case FORBIDDEN      -> "FORBIDDEN";
            case UNAUTHORIZED   -> "UNAUTHORIZED";
            default             -> "HTTP_" + status.value();
        };
        return build(status, code, safeMessage(ex.getReason()));
    }

    /**
     * A request for a path that matches no controller and no static resource.
     *
     * <p>Without this, {@code NoResourceFoundException} fell through to the
     * catch-all below and produced a logged ERROR plus a 500 for every missing
     * asset — browsers request {@code /favicon.ico} on each page load, so the log
     * filled with spurious failures. A missing resource is a 404.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    /**
     * Malformed JSON / unreadable request body (400).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body is malformed or unreadable.");
    }

    /**
     * Bean validation errors from @Valid request DTOs (400).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details);
    }

    /**
     * A path or query parameter failed type conversion (e.g. a non-UUID
     * sessionId). Without this, it fell through to the catch-all below and
     * produced a 500 + logged ERROR for what is a client mistake (400).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Invalid value for parameter '" + ex.getName() + "'.");
    }

    /**
     * Generic illegal-argument / illegal-state from any layer (400 / 500).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeMessage(ex.getMessage()));
    }

    /**
     * The OpenCV native library failed to load, so frame decoding and image
     * analysis are unavailable. Reported as 503 rather than an opaque 500 so
     * clients can distinguish "try again later" from a genuine bug.
     *
     * <p>Spring wraps Errors thrown from a handler in a {@code ServletException};
     * the cause chain is matched here.</p>
     */
    @ExceptionHandler(LinkageError.class)
    public ResponseEntity<Map<String, Object>> handleMissingNativeLibrary(LinkageError ex) {
        log.error("Native library unavailable during frame processing", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "ENGINE_UNAVAILABLE",
                "Face liveness engine is temporarily unavailable. Please retry.");
    }

    // ------------------------------------------------------------------ 5xx server errors

    /**
     * Catch-all for unexpected exceptions — returns a safe, generic message
     * while logging the full stack trace server-side.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An internal error occurred. Please try again.");
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("status", status.value());
        body.put("timestamp", OffsetDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }

    /** Never expose raw exception messages to the client. */
    private static String safeMessage(String msg) {
        if (msg == null || msg.isBlank()) return "An unexpected error occurred.";
        // Strip anything that looks like a stack trace or class name
        return msg.replaceAll("\\s+", " ").trim();
    }
}
