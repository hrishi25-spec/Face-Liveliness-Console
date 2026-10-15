package io.mosip.liveness.api;

import io.mosip.liveness.auth.AuthPrincipal;
import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.config.EffectivePolicyValidator;
import io.mosip.liveness.config.LivenessConfig;
import io.mosip.liveness.config.WorkflowPolicyDefaults;
import io.mosip.liveness.audit.AuditChain;
import io.mosip.liveness.audit.AuditChainKey;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.dto.AuditLogEntry;
import io.mosip.liveness.dto.ConfigPolicyResponse;
import io.mosip.liveness.dto.ConfigPolicyUpdate;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.AuthRole;
import io.mosip.liveness.models.enums.FailurePolicy;
import io.mosip.liveness.models.enums.WorkflowType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.services.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Config policy endpoints.
 * Maps to the Python framework's routes/config.py.
 *
 * Runtime updates to the config take effect immediately — the
 * DecisionEngineService reads fresh config from the DB on every call.
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigPolicyRepository configRepo;
    private final AuditLogRepository auditLogRepo;
    private final ConfigService configService;
    private final EffectivePolicyValidator effectivePolicyValidator;
    /** Keys the audit chain; see {@link AuditChainKey}. */
    private final AuditChainKey auditChainKey;

    /**
     * Shortest challenge window this deployment lets a policy configure — the
     * same knob the engine clamps to and the snapshot validator enforces
     * (default 15s). A policy below it cannot run as written, so the update is
     * refused here rather than saved and then rejected at session start.
     */
    @Value("${mosip.liveness.min-challenge-window-ms:" + LivenessConfig.MIN_CHALLENGE_WINDOW_MS + "}")
    private long minChallengeWindowMs;

    @GetMapping("/{workflowType}")
    public ConfigPolicyResponse getPolicy(@PathVariable WorkflowType workflowType) {
        ConfigPolicy policy = getOrCreatePolicy(workflowType);
        return toResponse(policy);
    }

    /**
     * Get the effective (computed) policy as the engine sees it.
     * Shows the merged result of DB config + engine defaults.
     */
    @GetMapping("/{workflowType}/effective")
    public EffectivePolicy getEffectivePolicy(@PathVariable WorkflowType workflowType) {
        return configService.getEffectivePolicy(configService.toCoreWorkflow(workflowType));
    }

    @PutMapping("/{workflowType}")
    @Transactional
    public ConfigPolicyResponse setPolicy(
            @PathVariable WorkflowType workflowType,
            @RequestBody ConfigPolicyUpdate update,
            HttpServletRequest request) {
        // Authorization first, deliberately: the caller's role is checked before
        // the body is even looked at, so a refused caller learns nothing about
        // which values are acceptable (and the field checks below stay in one
        // place, in this controller, rather than split across bean-validation
        // annotations that run before the role is verified). The gate above this
        // handler has already refused a stranger; this call is what makes the
        // handler safe on its own, in a slice or any embedding without the filter
        // chain.
        AuthPrincipal actor = AuthGate.requireRole(request, AuthRole.SUPERVISOR);

        // A policy row missing entirely is created here (same as the GET path);
        // knowing which happened is part of the audit story.
        Optional<ConfigPolicy> found = configRepo.findByWorkflowType(workflowType);
        boolean created = found.isEmpty();
        ConfigPolicy policy = found.orElseGet(() -> configRepo.save(newPolicy(workflowType)));

        // Validate before applying — a rejected update must change nothing, and
        // must not leave an audit entry for an edit that never happened.
        validate(update, policy);

        Map<String, Object> before = snapshot(policy);

        if (update.getLivenessEnabled() != null) policy.setLivenessEnabled(update.getLivenessEnabled());
        if (update.getPassiveThreshold() != null) policy.setPassiveThreshold(update.getPassiveThreshold());
        if (update.getActiveLivenessEnabled() != null) policy.setActiveLivenessEnabled(update.getActiveLivenessEnabled());
        if (update.getMinChallengeCount() != null) policy.setMinChallengeCount(update.getMinChallengeCount());
        if (update.getChallengeTypes() != null) policy.setChallengeTypes(update.getChallengeTypes());
        if (update.getChallengeTimeoutMs() != null) policy.setChallengeTimeoutMs(update.getChallengeTimeoutMs());
        if (update.getMaxRetryCount() != null) policy.setMaxRetryCount(update.getMaxRetryCount());
        if (update.getOnRepeatedFailure() != null) {
            policy.setOnRepeatedFailure(FailurePolicy.valueOf(update.getOnRepeatedFailure()));
        }

        // Cross-field invariants on the merged row: a per-field check cannot see
        // that e.g. minChallengeCount now exceeds the challenge-type pool, or
        // that the window would not run as configured.
        effectivePolicyValidator.validate(configService.mapToEffectivePolicy(policy));

        configRepo.save(policy);
        // Same transaction as the update: a rollback must not leave a phantom
        // "policy was changed" entry, and a saved policy must never be silent.
        auditPolicyChange(workflowType, created, before, policy, actor, request);
        return toResponse(policy);
    }

    /**
     * Audit view for policy edits: who changed which workflow, which fields, and
     * from what to what — newest first.
     *
     * <p>Open like the rest of the config reads ({@code GET /config/{workflowType}}
     * already publishes the current policy; hiding its history would be security
     * theatre). Only writes need the admin key.</p>
     */
    @GetMapping("/audit")
    public List<AuditLogEntry> configAudit(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) WorkflowType workflowType) {
        int capped = Math.max(1, Math.min(limit, 500));
        PageRequest page = PageRequest.of(0, capped);
        String eventType = AuditEventType.CONFIG_CHANGED.name();

        // Filtering happens in the query, not in the browser: audit_logs grows
        // one row per frame decision, so narrowing after the fact would page
        // through the entire pipeline history to answer a policy question. An
        // absent workflowType keeps the unfiltered feed.
        List<AuditLog> entries = workflowType == null
                ? auditLogRepo.findByEventTypeAndSessionIsNullOrderByCreatedAtDesc(eventType, page)
                : auditLogRepo.findByEventTypeAndWorkflowTypeAndSessionIsNullOrderByCreatedAtDesc(
                        eventType, workflowType, page);

        return entries.stream()
                .map(e -> AuditLogEntry.builder()
                        .id(e.getId())
                        .eventType(e.getEventType())
                        .workflowType(e.getWorkflowType() != null ? e.getWorkflowType().name() : null)
                        .details(e.getDetails())
                        .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                        .build())
                .toList();
    }

    /**
     * Records the field-level diff of a policy edit. Values are captured before
     * and after so the entry answers "what exactly changed", not just "someone
     * touched the config" — a threshold move from 0.80 to 0.00 and a no-op PUT
     * must not look alike.
     *
     * <p>The actor is recorded as an account name and a source IP, read from
     * {@link ClientIpResolver#ATTRIBUTE}. The name is the whole reason the shared
     * admin key was retired: this field used to hold a fingerprint of a value
     * anybody could hold, and it now names the person the policy edit belongs to.
     * The address narrows it to a host. Neither is an authentication input — the
     * address is read after the role check has already decided the caller is
     * allowed — so the open GET paths are untouched.</p>
     *
     * <p>Each entry also carries a {@link #classifyRisk(Map) risk}
     * classification, so the trail answers "should this worry me" without
     * an operator diffing two numbers by hand.</p>
     */
    private void auditPolicyChange(WorkflowType workflowType, boolean created,
                                   Map<String, Object> before, ConfigPolicy after,
                                   AuthPrincipal actor, HttpServletRequest request) {
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, Object> afterValues = snapshot(after);
        before.forEach((field, from) -> {
            Object to = afterValues.get(field);
            if (!Objects.equals(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from);
                change.put("to", to);
                changes.put(field, change);
            }
        });

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowType", workflowType.name());
        details.put("action", created ? "CREATED" : "UPDATED");
        details.put("actor", actorName(actor));
        details.put("sourceIp", sourceIp(request));
        details.put("changes", changes);
        // Computed over the diff and sealed inside the same details map
        // the chain hashes: the classification cannot be softened after
        // the fact without breaking the entry's own hash.
        details.put("risk", classifyRisk(changes));

        auditLogRepo.save(chained(
                AuditLog.builder()
                        .eventType(AuditEventType.CONFIG_CHANGED.name())
                        // Denormalised so the feed above can filter by workflow
                        // in SQL. The value stays in details as well — that JSON
                        // is the audit record's own payload, not just a query aid.
                        .workflowType(workflowType)
                        .details(details)
                        .build()));
    }

    /**
     * Rates how much an edit weakens the checks it configures.
     *
     * <p>The moves that silently defeat liveness are {@code HIGH}: a lower
     * passive threshold lets more spoofed frames through, and switching
     * either liveness switch off turns the check into a rubber stamp.
     * Everything else — a raised threshold, tightened retries — is
     * {@code LOW}. {@code reasons} names which weakening move fired, so
     * the console can flag the edit without the reader re-deriving it
     * from the diff.</p>
     *
     * <p>Only fields present in {@code changes} are considered: an edit
     * that did not move a field cannot have weakened it, and a no-op PUT
     * classifies as the LOW it is.</p>
     */
    private static Map<String, Object> classifyRisk(Map<String, Object> changes) {
        List<String> reasons = new ArrayList<>();
        if (lowered(changes, "passiveThreshold")) {
            reasons.add("passiveThreshold lowered");
        }
        if (switchedOff(changes, "livenessEnabled")) {
            reasons.add("liveness disabled");
        }
        if (switchedOff(changes, "activeLivenessEnabled")) {
            reasons.add("active liveness disabled");
        }
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("level", reasons.isEmpty() ? "LOW" : "HIGH");
        risk.put("reasons", reasons);
        return risk;
    }

    /** A numeric field whose new value is strictly below its old one. */
    private static boolean lowered(Map<String, Object> changes, String field) {
        return changes.get(field) instanceof Map<?, ?> moved
                && moved.get("from") instanceof Number from
                && moved.get("to") instanceof Number to
                && to.doubleValue() < from.doubleValue();
    }

    /** A boolean field flipped from its on-state to its off-state. */
    private static boolean switchedOff(Map<String, Object> changes, String field) {
        return changes.get(field) instanceof Map<?, ?> moved
                && Boolean.TRUE.equals(moved.get("from"))
                && Boolean.FALSE.equals(moved.get("to"));
    }

    /**
     * Links a new entry onto the chain before it is saved.
     *
     * <p>The read of the current head and the write of the successor must be
     * atomic: two admin requests arriving together would otherwise both chain
     * onto the same predecessor and fork the trail into two branches that each
     * verify perfectly on their own. The service is a single process (the
     * limiter's counters assume the same), so a local monitor is sufficient; a
     * multi-node deployment would need the uniqueness enforced by the database
     * instead.</p>
     *
     * <p>{@code createdAt} is assigned by the entity's {@code @PrePersist}, which
     * runs inside {@code save()} — after this method. The hash therefore has to
     * be computed over the timestamp the row will actually carry, so it is set
     * here and {@code @PrePersist} leaves it alone.</p>
     */
    private AuditLog chained(AuditLog entry) {
        synchronized (chainLock) {
            AuditLog head = auditLogRepo
                    .findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(
                            AuditEventType.CONFIG_CHANGED.name())
                    .orElse(null);
            entry.setPrevHash(head != null ? head.getEntryHash() : AuditChain.GENESIS);

            OffsetDateTime now = OffsetDateTime.now();
            // The chain is walked in created_at order, so two edits inside the
            // same millisecond would leave the ordering ambiguous and a perfectly
            // intact trail would fail verification — a false alarm on a security
            // control, which is its own kind of bug. Nudge forward instead.
            if (head != null && head.getCreatedAt() != null
                    && !now.isAfter(head.getCreatedAt())) {
                // OffsetDateTime is LocalDateTime-based, so its finest unit is nanos.
                now = head.getCreatedAt().plusNanos(1_000_000L);   // +1 ms
            }
            entry.setCreatedAt(now);
            entry.setEntryHash(AuditChain.hashOf(entry, auditChainKey));
        }
        return entry;
    }

    /**
     * Reports whether the config audit chain still verifies.
     *
     * <p>Open read, like the feed above: an audit trail you can only check by
     * connecting to the database yourself is not one anybody checks. Storing
     * the hashes without a way to walk them makes the control inert — this is
     * what turns it into something an operator can act on.</p>
     *
     * <p>Walks the entire chain, not a page: a break past the page boundary
     * would otherwise read as "intact".</p>
     */
    @GetMapping("/audit/verify")
    public Map<String, Object> verifyConfigAuditChain() {
        List<AuditLog> chain = auditLogRepo.findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(
                AuditEventType.CONFIG_CHANGED.name());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", AuditEventType.CONFIG_CHANGED.name());
        result.put("chainedEntries", chain.size());
        result.put("headHash", chain.isEmpty() ? null : chain.get(chain.size() - 1).getEntryHash());
        // The mode the walk below actually used. Without it, a chain verified
        // against the SHA-256 fallback reads exactly like a keyed one, and an
        // operator would take "intact" as stronger than it is: it only says a
        // database writer cannot rebuild this trail.
        result.put("hmac", auditChainKey.isKeyed());
        AuditChain.Verification verification = AuditChain.verifyChain(chain, auditChainKey);
        Optional<AuditChain.Break> broken = verification.breakInfo();
        // Distinguish the configured overlap from historical hashes that
        // still require the previous key. Immutable rows cannot be re-keyed
        // in place, so both states matter to an operator.
        result.put("rotationWindowOpen", auditChainKey.isRotating());
        result.put("retiredKeyHashes", verification.retiredKeyHashes());
        result.put("intact", broken.isEmpty());
        broken.ifPresent(b -> {
            result.put("breakIndex", b.index());
            result.put("breakEntryId", b.id());
            result.put("storedHash", b.storedHash());
            result.put("recomputedHash", b.recomputedHash());
            result.put("storedPrevHash", b.storedPrevHash());
            result.put("expectedPrevHash", b.expectedPrevHash());
            // "contentChanged" separates an edited row from a deleted one; the
            // remedy is different (restore the row vs account for the gap).
            result.put("contentChanged", b.contentChanged());
        });
        return result;
    }

    /** Guards the read-head/write-head pair; see {@link #chained(AuditLog)}. */
    private final Object chainLock = new Object();

    /** The policy fields a client can change — the diff's field list. */
    private static Map<String, Object> snapshot(ConfigPolicy p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("livenessEnabled", p.getLivenessEnabled());
        values.put("passiveThreshold", p.getPassiveThreshold());
        values.put("activeLivenessEnabled", p.getActiveLivenessEnabled());
        values.put("minChallengeCount", p.getMinChallengeCount());
        values.put("challengeTypes", p.getChallengeTypes() == null
                ? null : List.copyOf(p.getChallengeTypes()));
        values.put("challengeTimeoutMs", p.getChallengeTimeoutMs());
        values.put("maxRetryCount", p.getMaxRetryCount());
        values.put("onRepeatedFailure", p.getOnRepeatedFailure() == null
                ? null : p.getOnRepeatedFailure().name());
        return values;
    }/**
     * The caller's address, as {@link ClientIpFilter} resolved it.
     *
     * <p>Optional by design: the attribute is absent when a controller is
     * reached without the filter in the chain (a {@code @WebMvcTest} slice that
     * does not register it, a direct call from another service). Recording
     * "unknown" keeps the JSON shape stable, so a reader can tell "we could not
     * tell" apart from a field that is simply missing.</p>
     *
     * <p>ponytail: the value is length-capped and restricted to hex digits,
     * dots and colons because a trusted proxy relays {@code X-Forwarded-For}
     * without validating it, and this text is persisted and shown in the
     * console. That bounds a row the whoever-the-proxy-forwarded-for chooses,
     * and leaves nothing that could break a log line or a JSON viewer. A
     * stricter "must parse as an IP literal" check would mislabel IPv4-mapped
     * IPv6 peers, which arrive as {@code ::ffff:10.0.0.7}.</p>
     */
    private static String sourceIp(HttpServletRequest request) {
        Object ip = request != null ? request.getAttribute(ClientIpResolver.ATTRIBUTE) : null;
        return ip instanceof String s && ADDRESS_TEXT.matcher(s).matches() ? s : "unknown";
    }

    /** Dotted quad or IPv6 text; 45 chars covers the longest legal form. */
    private static final Pattern ADDRESS_TEXT = Pattern.compile("^[0-9a-fA-F.:]{1,45}$");

    /**
     * The named account this policy edit belongs to.
     *
     * <p>An account name rather than the key fingerprint this used to store. The
     * fingerprint answered "someone holding that key did it" — which is exactly
     * what an insider, a contractor who has left, and a leaked environment file
     * all look like. The name is the accountable party, and it comes from a
     * session the service issued and can revoke.</p>
     *
     * <p>The fallback is not reachable on this route — the role check above
     * requires {@code SUPERVISOR}, which the account-less operator key can never
     * prove — but an audit field that could be written as {@code null} would make
     * a later reader wonder whether the value was missing or the actor unknown.</p>
     */
    private static String actorName(AuthPrincipal actor) {
        return actor != null && actor.username() != null ? actor.username() : "unknown";
    }

    private void validate(ConfigPolicyUpdate update, ConfigPolicy currentPolicy) {
        if (update.getPassiveThreshold() != null &&
                (update.getPassiveThreshold() <= 0.0 || update.getPassiveThreshold() > 1.0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "passiveThreshold must be greater than 0.0 and at most 1.0");
        }
        if (update.getMinChallengeCount() != null && update.getMinChallengeCount() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minChallengeCount must be >= 1");
        }
        long windowFloor = LivenessConfig.effectiveMinChallengeWindowMs(minChallengeWindowMs);
        if (update.getChallengeTimeoutMs() != null && update.getChallengeTimeoutMs() < windowFloor) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "challengeTimeoutMs must be >= " + windowFloor
                            + "ms (mosip.liveness.min-challenge-window-ms)");
        }
        if (update.getMaxRetryCount() != null && update.getMaxRetryCount() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxRetryCount must be >= 0");
        }
        if (update.getOnRepeatedFailure() != null) {
            try {
                FailurePolicy.valueOf(update.getOnRepeatedFailure());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "onRepeatedFailure must be one of: LOCK, ESCALATE, ALLOW_RETRY");
            }
        }
        // challengeTypes must not be empty when active liveness is enabled
        boolean activeLivenessEnabled = currentPolicy.getActiveLivenessEnabled();
        if (update.getActiveLivenessEnabled() != null) {
            activeLivenessEnabled = update.getActiveLivenessEnabled();
        }
        if (update.getChallengeTypes() != null && update.getChallengeTypes().isEmpty() && activeLivenessEnabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "challengeTypes must not be empty when activeLiveness is enabled");
        }
    }

    private ConfigPolicy getOrCreatePolicy(WorkflowType workflowType) {
        return configRepo.findByWorkflowType(workflowType)
                .orElseGet(() -> configRepo.save(newPolicy(workflowType)));
    }

    /**
     * Defaults for a workflow that has no {@code config_policies} row yet, taken
     * from the same per-workflow source the V4 migration seeds from — so a
     * lazily-created row is identical to a migrated one instead of silently
     * collapsing all three workflows onto one operating point.
     */
    private ConfigPolicy newPolicy(WorkflowType workflowType) {
        EffectivePolicy defaults = WorkflowPolicyDefaults.forWorkflow(
                configService.toCoreWorkflow(workflowType));
        return ConfigPolicy.builder()
                .workflowType(workflowType)
                .livenessEnabled(defaults.livenessEnabled())
                .passiveThreshold(defaults.passiveThreshold())
                .activeLivenessEnabled(defaults.activeLivenessEnabled())
                .minChallengeCount(defaults.minChallengeCount())
                .challengeTypes(defaults.allowedChallenges().stream()
                        .map(c -> configService.toDbChallenge(c).name().toLowerCase())
                        .toList())
                .challengeTimeoutMs((int) defaults.challengeTimeoutMs())
                .maxRetryCount(defaults.maxRetries())
                .onRepeatedFailure(configService.toDbFailure(defaults.onRepeatedFailure()))
                .build();
    }

    private ConfigPolicyResponse toResponse(ConfigPolicy p) {
        return ConfigPolicyResponse.builder()
                .id(p.getId())
                .workflowType(p.getWorkflowType())
                .livenessEnabled(p.getLivenessEnabled())
                .passiveThreshold(p.getPassiveThreshold())
                .activeLivenessEnabled(p.getActiveLivenessEnabled())
                .minChallengeCount(p.getMinChallengeCount())
                .challengeTypes(p.getChallengeTypes())
                .challengeTimeoutMs(p.getChallengeTimeoutMs())
                .maxRetryCount(p.getMaxRetryCount())
                .onRepeatedFailure(p.getOnRepeatedFailure())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
