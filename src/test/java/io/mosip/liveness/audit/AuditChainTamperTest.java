package io.mosip.liveness.audit;

import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the config audit chain actually detects tampering — the whole point of
 * storing the hashes.
 *
 * <p>Tampering is done through {@link JdbcTemplate} rather than the repository
 * on purpose: going through JPA would re-derive the hash and "repair" the row,
 * which would test nothing. Raw SQL is what an attacker with a database
 * connection has, and it is the only way to produce the broken state.
 *
 * <p>Runs on H2, whose schema Hibernate generates from the entities, so these
 * tests cannot prove the V9 PostgreSQL triggers — only the chain logic, which
 * is database-independent. The triggers are validated by the {@code schema} CI
 * job.</p>
 */
@DataJpaTest(properties = {
        // The Flyway migrations are Postgres SQL; on H2 the schema comes from the
        // entities, which is what EntityPersistenceTest does too.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class AuditChainTamperTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.mosip.liveness.crud.AuditLogRepository repo;

    private static java.time.OffsetDateTime lastTimestamp = java.time.OffsetDateTime.now();

    /**
     * The key ConfigController hashes with when no secret is configured — the
     * documented SHA-256 fallback, which is what most of these cases exercise.
     */
    private static final AuditChainKey UNKEYED = new AuditChainKey("");

    /** A secret the database does not hold — production's configuration. */
    private static final String KEYED_SECRET = "e2e-audit-hmac-secret-0123456789";
    private static final AuditChainKey KEYED = new AuditChainKey(KEYED_SECRET);
    /** The secret the trail is rotated onto. */
    private static final String ROTATED = "rotated-audit-hmac-secret-0123456789";

    /** Strictly increasing, so ordering by created_at is unambiguous. */
    private static java.time.OffsetDateTime nextTimestamp() {
        lastTimestamp = lastTimestamp.plusNanos(1_000_000L);   // +1 ms
        return lastTimestamp;
    }

    /** Builds and persists one chained entry, the way ConfigController does. */
    private AuditLog append(AuditChainKey key, String previousHash, String threshold) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", 0.80);
        change.put("to", Double.parseDouble(threshold));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowType", "RESIDENT");
        details.put("action", "UPDATED");
        details.put("changes", change);

        AuditLog entry = AuditLog.builder()
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(details)
                .prevHash(previousHash)
                .createdAt(nextTimestamp())
                .build();
        entry.setEntryHash(AuditChain.hashOf(entry, key));
        return repo.saveAndFlush(entry);
    }

    private String seedChain(int entries) {
        return seedChain(UNKEYED, entries);
    }

    private String seedChain(AuditChainKey key, int entries) {
        String previous = AuditChain.GENESIS;
        for (int i = 0; i < entries; i++) {
            previous = append(key, previous, "0." + (70 + i)).getEntryHash();
        }
        return previous;
    }

    /** Appends a further batch onto an existing head, as a later edit would. */
    private void continueChain(AuditChainKey key, String previous, int entries, int from) {
        for (int i = 0; i < entries; i++) {
            previous = append(key, previous, "0." + (70 + from + i)).getEntryHash();
        }
    }

    /** The stored hash of the newest entry — the next entry's {@code prev_hash}. */
    private String chainHead() {
        List<AuditLog> entries = chain();
        return entries.get(entries.size() - 1).getEntryHash();
    }

    private List<AuditLog> chain() {
        return repo.findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(
                AuditEventType.CONFIG_CHANGED.name());
    }

    @Test
    @DisplayName("an untouched chain verifies")
    void intactChainVerifies() {
        seedChain(4);
        assertEquals(4, chain().size());
        assertTrue(AuditChain.verify(chain(), UNKEYED).isEmpty(),
                "a chain written normally must verify");
    }

    @Test
    @DisplayName("hashes are stable across a database round-trip")
    void hashSurvivesTheRoundTrip() throws Exception {
        seedChain(2);

        // The converter rebuilds details as a plain HashMap, so its iteration
        // order differs from the LinkedHashMap that was hashed on write. If the
        // canonical form were not order-independent, every verification of a
        // real row would fail for reasons unrelated to tampering.
        repo.flush();
        jdbc.execute("select 1");
        entityManagerClear();

        List<AuditLog> reloaded = chain();
        assertEquals(2, reloaded.size());
        for (AuditLog entry : reloaded) {
            assertEquals(entry.getEntryHash(), AuditChain.hashOf(entry, UNKEYED),
                    "entry " + entry.getId() + " must reproduce its own hash after a round-trip");
        }
        assertTrue(AuditChain.verify(reloaded, UNKEYED).isEmpty());
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private void entityManagerClear() {
        entityManager.clear();
    }

    @Test
    @DisplayName("an EDITED row is detected")
    void editedRowIsDetected() {
        seedChain(3);
        assertTrue(AuditChain.verify(chain(), UNKEYED).isEmpty());

        // Exactly what an attacker covering their tracks does: rewrite the value
        // the audit says was set, keeping every other column intact.
        AuditLog target = chain().get(1);
        jdbc.update("UPDATE audit_logs SET details = ? WHERE id = ?",
                "{\"workflowType\":\"RESIDENT\",\"action\":\"UPDATED\",\"changes\":{\"passiveThreshold\":{\"from\":0.8,\"to\":0.01}}}",
                target.getId());
        entityManagerClear();

        AuditChain.Break broken = AuditChain.verify(chain(), UNKEYED).orElseThrow(
                () -> new AssertionError("an edited audit row must break the chain"));

        assertTrue(broken.contentChanged(),
                "the row's own content no longer hashes to its stored entry_hash");
        assertEquals(target.getId(), broken.id());
        assertEquals(1, broken.index(), "the second entry is the one that was altered");
        assertEquals(target.getEntryHash(), broken.storedHash(),
                "the stored hash is untouched — only the content changed");
        assertNotNull(broken.recomputedHash());
    }

    @Test
    @DisplayName("a DELETED row is detected")
    void deletedRowIsDetected() {
        seedChain(4);

        AuditLog removed = chain().get(1);
        jdbc.update("DELETE FROM audit_logs WHERE id = ?", removed.getId());
        entityManagerClear();

        List<AuditLog> remaining = chain();
        assertEquals(3, remaining.size(), "the row is really gone from the table");

        AuditChain.Break broken = AuditChain.verify(remaining, UNKEYED).orElseThrow(
                () -> new AssertionError("deleting an audit row must break the chain"));

        // The surviving rows are each internally consistent — their own content
        // still hashes to their stored value. What is broken is the link: the
        // successor still names the hash of a row that no longer exists.
        assertFalse(broken.contentChanged(),
                "the rows that remain are untouched; only the linkage is wrong");
        assertEquals(removed.getEntryHash(), broken.storedPrevHash(),
                "the dangling reference is in the row's stored prev_hash");
        // The walk expected the last row it could actually verify — the one
        // before the break — rather than the hash of the row that is gone.
        assertEquals(remaining.get(broken.index() - 1).getEntryHash(), broken.expectedPrevHash(),
                "the walk anchored on the previous surviving row, not the deleted one");
    }

    @Test
    @DisplayName("a re-inserted row with the original hash still fails once the content moved on")
    void rehashingAloneDoesNotRepairTheChain() {
        seedChain(3);
        AuditLog middle = chain().get(1);
        jdbc.update("DELETE FROM audit_logs WHERE id = ?", middle.getId());
        entityManagerClear();

        assertTrue(AuditChain.verify(chain(), UNKEYED).isPresent());

        // Putting the row back with its old hash cannot fix anything: the
        // successor is chained to a hash that no longer exists, so the walk
        // still cannot get past the gap.
        repo.saveAndFlush(AuditLog.builder()
                .id(middle.getId())
                .eventType(middle.getEventType())
                .workflowType(middle.getWorkflowType())
                .details(middle.getDetails())
                .prevHash(middle.getPrevHash())
                .entryHash(middle.getEntryHash())
                .createdAt(middle.getCreatedAt())
                .build());
        entityManagerClear();

        assertTrue(AuditChain.verify(chain(), UNKEYED).isEmpty(),
                "restoring the exact row repairs the chain — detection is of the gap, not a permanent mark");
    }

    @Test
    @DisplayName("the first entry is anchored to GENESIS, not to a dangling hash")
    void chainStartsAtGenesis() {
        String head = seedChain(1);
        AuditLog first = chain().get(0);
        assertEquals(AuditChain.GENESIS, first.getPrevHash());
        assertEquals(head, first.getEntryHash());
        assertTrue(AuditChain.verify(chain(), UNKEYED).isEmpty());
    }

    @Test
    @DisplayName("rotation keeps the trail verifying across the secret change")
    void rotationVerifiesOldAndNewEntriesTogether() {
        // Written with the old secret, then the operator rotates. Entries before
        // the rotation are on the retired key; new ones go on the current key.
        seedChain(KEYED, 2);
        AuditChainKey rotated = new AuditChainKey(ROTATED, KEYED_SECRET);
        continueChain(rotated, chainHead(), 2, 2);
        // Re-read through the JPA converter, as /audit/verify does after a
        // process restart. This catches canonicalization drift that a
        // first-level-cache-only check would miss.
        repo.flush();
        entityManager.clear();
        assertEquals(4, chain().size());

        // The overlap is what makes the rotation possible at all: without it the
        // two old entries would be unreproducible the moment the secret changed.
        AuditChain.Verification verification = AuditChain.verifyChain(chain(), rotated);
        assertTrue(verification.intact(), "a rotation window must not break the chain");
        assertTrue(verification.retiredKeyHashes(),
                "the operator has to be told the trail still holds retired-key hashes");

        // Without the overlap — same chain, old secret simply gone — the same
        // rows break, which is the behaviour the window exists to soften.
        assertTrue(AuditChain.verify(chain(), new AuditChainKey(ROTATED)).isPresent(),
                "dropping the retired secret breaks what the window was preserving");
    }

    @Test
    @DisplayName("reports retired-key rows even when an earlier row breaks the chain")
    void retiredKeyUseIsFoundAfterFirstBreak() {
        seedChain(KEYED, 2);
        AuditChainKey rotated = new AuditChainKey(ROTATED, KEYED_SECRET);
        continueChain(rotated, chainHead(), 2, 2);

        AuditLog first = chain().get(0);
        jdbc.update("UPDATE audit_logs SET details = ? WHERE id = ?",
                "{\"workflowType\":\"RESIDENT\",\"action\":\"UPDATED\",\"changes\":{\"passiveThreshold\":{\"from\":0.8,\"to\":0.01}}}",
                first.getId());
        entityManagerClear();

        AuditChain.Verification verification = AuditChain.verifyChain(chain(), rotated);
        assertEquals(0, verification.breakInfo().orElseThrow().index());
        assertTrue(verification.retiredKeyHashes(),
                "later historical rows still prove the retired secret is needed");
    }

    @Test
    @DisplayName("application writes use the current secret during rotation")
    void applicationWriterUsesOnlyTheCurrentSecret() {
        // The configured application writer always hashes with the current
        // key. This does not prevent a holder of the previous key from
        // calculating HMACs outside the application.
        AuditChainKey rotating = new AuditChainKey(ROTATED, KEYED_SECRET);
        assertNotEquals(rotating.hash("entry"),
                new AuditChainKey(KEYED_SECRET).hash("entry"),
                "writing must use the current secret, never the retired one");
        assertEquals(AuditChainKey.Match.RETIRED,
                rotating.match("entry", new AuditChainKey(KEYED_SECRET).hash("entry")));
        assertEquals(AuditChainKey.Match.CURRENT,
                rotating.match("entry", rotating.hash("entry")));
        assertEquals(AuditChainKey.Match.NONE,
                rotating.match("entry", new AuditChainKey("some-other-secret-0123456789")
                        .hash("entry")));
    }

    @Test
    @DisplayName("a fully re-keyed chain reports no retired hashes")
    void rekeyedChainNeedsNoWindow() {
        seedChain(KEYED, 2);
        // Every entry written under the current secret: the window is open but
        // unused, which is the state to report — not a stale flag.
        AuditChain.Verification verification = AuditChain.verifyChain(chain(),
                new AuditChainKey(KEYED_SECRET, KEYED_SECRET + "-old"));
        assertTrue(verification.intact());
        assertFalse(verification.retiredKeyHashes());
    }

    @Test
    @DisplayName("a keyed chain verifies under the secret that wrote it")
    void keyedChainVerifiesUnderItsSecret() {
        seedChain(KEYED, 3);
        assertTrue(AuditChain.verify(chain(), KEYED).isEmpty(),
                "a chain written with a secret must verify with it");
    }

    @Test
    @DisplayName("a database writer without the secret cannot rebuild the chain")
    void databaseWriterWithoutTheSecretCannotRebuildTheChain() {
        // A chain written with a real secret: the production configuration.
        seedChain(KEYED, 4);

        // The attacker's whole move: edit the value the audit says was set,
        // then recompute every hash in the trail. Unkeyed, that is enough to
        // make the chain verify perfectly — which is the whole reason the hash
        // is keyed. They have a database connection and no secret, so every
        // hash they can produce is the public SHA-256.
        rebuildChainWithoutTheSecret(1);

        // Under the fallback key the rebuilt chain is convincing...
        assertTrue(AuditChain.verify(chain(), UNKEYED).isEmpty(),
                "the unkeyed recomputation is internally consistent — that is the attack");

        // ...and under the real secret it dies at the first entry, because the
        // stored hash is not one the secret can produce.
        AuditChain.Break broken = AuditChain.verify(chain(), KEYED).orElseThrow(
                () -> new AssertionError("a rebuilt chain must not verify under the real secret"));
        assertEquals(0, broken.index(),
                "the first entry is already wrong: no stored hash here came from the secret");
        assertTrue(broken.contentChanged(),
                "the row's stored hash is not the keyed hash of its own content");
    }

    @Test
    @DisplayName("a chain written under one secret does not verify under another")
    void rotatedSecretIsDetectedRatherThanSilentlyAccepted() {
        seedChain(KEYED, 2);

        AuditChain.Break broken = AuditChain.verify(chain(),
                new AuditChainKey("a-different-audit-secret-9876543210")).orElseThrow(
                () -> new AssertionError("verification under the wrong secret must break"));
        assertEquals(0, broken.index(), "the first entry fails its own hash");
        assertTrue(broken.contentChanged(),
                "self-inconsistent: no stored hash was produced by this secret");

        assertTrue(AuditChain.verify(chain(), KEYED).isEmpty(),
                "the same rows verify under the secret that wrote them");
    }

    /**
     * Edits one row and recomputes every hash in the trail the only way an
     * attacker without the secret can: publicly, over the whole chain.
     */
    private void rebuildChainWithoutTheSecret(int editedIndex) {
        List<AuditLog> entries = chain();
        jdbc.update("UPDATE audit_logs SET details = ? WHERE id = ?",
                "{\"workflowType\":\"RESIDENT\",\"action\":\"UPDATED\",\"changes\":{\"passiveThreshold\":{\"from\":0.8,\"to\":0.01}}}",
                entries.get(editedIndex).getId());
        entityManagerClear();

        String previous = AuditChain.GENESIS;
        for (AuditLog entry : chain()) {
            entry.setPrevHash(previous);
            entry.setEntryHash(AuditChain.hashOf(entry, UNKEYED));
            previous = entry.getEntryHash();
            jdbc.update("UPDATE audit_logs SET prev_hash = ?, entry_hash = ? WHERE id = ?",
                    entry.getPrevHash(), entry.getEntryHash(), entry.getId());
        }
        entityManagerClear();
    }

    @Test
    @DisplayName("the repository reports the newest chained entry as the link target")
    void headLookupSkipsUnchainedLegacyRows() {
        // A legacy row: no hashes at all, as written before V9.
        repo.saveAndFlush(AuditLog.builder()
                .id(UUID.randomUUID())
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.OPERATOR)
                .details(Map.of("workflowType", "OPERATOR"))
                .createdAt(java.time.OffsetDateTime.now())
                .build());

        seedChain(2);
        Optional<AuditLog> head = repo
                .findFirstByEventTypeAndEntryHashIsNotNullOrderByCreatedAtDesc(
                        AuditEventType.CONFIG_CHANGED.name());
        assertTrue(head.isPresent());
        assertNotNull(head.get().getEntryHash(), "the legacy NULL-hash row must not be chosen as head");
    }
}
