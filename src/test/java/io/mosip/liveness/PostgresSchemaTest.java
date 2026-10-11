package io.mosip.liveness;

import io.mosip.liveness.audit.AuditChain;
import io.mosip.liveness.audit.AuditChainKey;
import io.mosip.liveness.audit.AuditEventType;
import io.mosip.liveness.crud.AuditLogRepository;
import io.mosip.liveness.crud.LivenessSessionRepository;
import io.mosip.liveness.models.entity.AuditLog;
import io.mosip.liveness.models.entity.LivenessSession;
import io.mosip.liveness.models.enums.LivenessStage;
import io.mosip.liveness.models.enums.SessionStatus;
import io.mosip.liveness.models.enums.WorkflowType;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema gate: boots the real application against a real PostgreSQL, lets
 * Flyway build the schema from the migrations, and has Hibernate
 * {@code ddl-auto=validate} compare that schema against the entities.
 *
 * <p>This exists because nothing else does that. The dev profile and every
 * {@code @DataJpaTest} run on H2 with {@code ddl-auto: create-drop}, so
 * Hibernate invents the schema from the entities and Flyway never runs. That
 * combination hides a whole class of drift: a column the entity declares but
 * no migration creates, a {@code VARCHAR(16)} that Postgres will happily
 * accept for a few hundred rows and then reject, an enum stored as a number,
 * a {@code NOT NULL} the migration is missing. Green CI, broken production —
 * and the production target is PostgreSQL, which is the one database that
 * never gets tested.</p>
 *
 * <p>Testcontainers starts an isolated PostgreSQL for the class, so the gate
 * runs locally and in CI without a preconfigured service or shared database.
 * It skips automatically when Docker is unavailable, leaving the ordinary
 * H2-only suite usable on machines without a container runtime.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@org.springframework.test.context.TestPropertySource(properties = {
        "MOSIP_ADMIN_API_KEY=test-admin-key-that-is-long-enough-for-validation"
})
class PostgresSchemaTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pad_liveness")
            .withUsername("mosip")
            .withPassword("test-postgres-password")
            .withInitScript("docker/initdb/01-least-privilege-role.sql");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "pad_runtime");
        properties.add("spring.datasource.password", () -> "test-postgres-password");
        // Flyway runs as pad_migrate (migration role with DDL permissions)
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", () -> "pad_migrate");
        properties.add("spring.flyway.password", () -> "test-postgres-password");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LivenessSessionRepository sessions;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("is talking to a real PostgreSQL, not the H2 stand-in")
    void isRealPostgres() throws Exception {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        }
        // Without this guard the test could pass on H2 if someone pointed
        // POSTGRES_HOST somewhere unexpected, which is the exact failure mode
        // this whole test exists to rule out.
        assertTrue(product.toLowerCase().contains("postgresql"),
                "expected PostgreSQL but connected to " + product
                        + " — this gate is meaningless against any other database");
    }

    @Test
    @DisplayName("validates the schema instead of creating it")
    void ddlAutoIsValidate() {
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto");
        assertEquals("validate", ddlAuto,
                "Hibernate must only validate; create/update would mask entity-vs-migration drift "
                        + "by rebuilding the schema from the entities");
        assertTrue(environment.getProperty("spring.flyway.enabled", Boolean.class, false),
                "Flyway must own the schema for this test to mean anything");
    }

    @Test
    @DisplayName("applies every committed migration")
    void flywayAppliedAllMigrations() throws Exception {
        // Flyway builds the schema; if it silently no-opped, `validate` would
        // be checking an empty database and would fail loudly — but this makes
        // the reason legible instead of mysterious.
        //
        // The expectation is read off the committed migration files rather than
        // hardcoded: this assertion used to pin the latest version to V4, so the
        // moment the migrations were renumbered past V4 the gate went red for a
        // reason that had nothing to do with the schema.
        Resource[] committed = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*.sql");
        MigrationVersion latestCommitted = null;
        for (Resource resource : committed) {
            String name = resource.getFilename();
            assertNotNull(name, "classpath migration resource has no filename");
            int separator = name.indexOf("__");
            assertTrue(separator > 1,
                    "migration filename must be V<version>__<description>.sql, found " + name);
            MigrationVersion version = MigrationVersion.fromVersion(name.substring(1, separator));
            if (latestCommitted == null || version.compareTo(latestCommitted) > 0) {
                latestCommitted = version;
            }
        }
        assertNotNull(latestCommitted, "no V*.sql migrations found on the classpath");

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= committed.length,
                "expected at least one history row per committed migration ("
                        + committed.length + " files, latest V" + latestCommitted
                        + "), found " + applied);

        String latest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertEquals(latestCommitted.toString(), latest,
                "latest applied migration should be V" + latestCommitted
                        + " — every committed migration must be applied");
    }

    @Test
    @DisplayName("round-trips an entity through the migrated schema")
    void entityRoundTrips() {
        // Proves the mapping is not merely "no missing table": the row actually
        // inserts and reads back with its enum columns and timestamptz intact.
        LivenessSession saved = sessions.saveAndFlush(LivenessSession.builder()
                .workflowType(WorkflowType.RESIDENT)
                .deviceId("schema-gate")
                .subjectRef("subject-42")
                .status(SessionStatus.ACTIVE)
                .currentStage(LivenessStage.PASSIVE)
                .build());

        assertNotNull(saved.getId());
        // OffsetDateTime against a TIMESTAMPTZ column is the classic
        // H2-passes/Postgres-fails mapping difference, so assert on it.
        assertNotNull(saved.getCreatedAt(), "@PrePersist must populate created_at");

        LivenessSession reloaded = sessions.findById(saved.getId()).orElseThrow();
        assertEquals(SessionStatus.ACTIVE, reloaded.getStatus());
        assertEquals(WorkflowType.RESIDENT, reloaded.getWorkflowType());
        assertEquals("subject-42", reloaded.getSubjectRef());

        sessions.deleteById(saved.getId());
        sessions.flush();
        assertTrue(sessions.findById(saved.getId()).isEmpty());
        assertEquals(UUID.class, reloaded.getId().getClass());
    }

    @Test
    @DisplayName("verifies old and current audit keys after a PostgreSQL round-trip")
    void keyedAuditChainSurvivesRotationAndReload() {
        String previousSecret = "postgres-audit-old-key-0123456789";
        String currentSecret = "postgres-audit-current-key-0123456789";
        AuditChainKey previous = new AuditChainKey(previousSecret);
        AuditChainKey rotating = new AuditChainKey(currentSecret, previousSecret);
        OffsetDateTime firstTime = OffsetDateTime.now().withNano(0);

        AuditLog oldEntry = appendAudit(previous, AuditChain.GENESIS, firstTime, "0.75");
        appendAudit(rotating, oldEntry.getEntryHash(), firstTime.plusSeconds(1), "0.70");

        // Force the JSON converter and PostgreSQL timestamp mapping to run, as
        // they do between a write and a later /audit/verify request.
        entityManager.clear();
        List<AuditLog> chain = auditLogs.findByEventTypeAndEntryHashIsNotNullOrderByCreatedAtAsc(
                AuditEventType.CONFIG_CHANGED.name());

        assertEquals(2, chain.size());
        AuditChain.Verification verification = AuditChain.verifyChain(chain, rotating);
        assertTrue(verification.intact(), "old and current key hashes must keep the chain intact");
        assertTrue(verification.retiredKeyHashes(), "the old row must be reported as still needed");
    }

    private AuditLog appendAudit(AuditChainKey key, String previousHash,
                                 OffsetDateTime createdAt, String threshold) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", 0.80);
        change.put("to", Double.parseDouble(threshold));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("passiveThreshold", change);
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("level", "HIGH");
        risk.put("reasons", List.of("passiveThreshold lowered"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowType", "RESIDENT");
        details.put("action", "UPDATED");
        details.put("actor", "key:9f2c1a7b4e0d");
        details.put("sourceIp", "127.0.0.1");
        details.put("changes", changes);
        details.put("risk", risk);

        AuditLog entry = AuditLog.builder()
                .eventType(AuditEventType.CONFIG_CHANGED.name())
                .workflowType(WorkflowType.RESIDENT)
                .details(details)
                .prevHash(previousHash)
                .createdAt(createdAt)
                .build();
        entry.setEntryHash(AuditChain.hashOf(entry, key));
        return auditLogs.saveAndFlush(entry);
    }
}
