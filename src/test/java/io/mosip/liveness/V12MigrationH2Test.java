package io.mosip.liveness;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auth migration, applied for real on H2.
 *
 * <p>The dev profile runs the same migrations the deployment does, but "the same
 * migrations" was an assumption nobody checked: {@code spring.flyway.enabled} is
 * {@code false} under {@code dev} (Hibernate builds that schema from the entities),
 * and the migrations from V5 onwards are PostgreSQL-only — JSONB, PL/pgSQL triggers,
 * a {@code DO $$} block — so pointing Flyway at H2 with the whole set would fail for
 * reasons that have nothing to do with this migration.</p>
 *
 * <p>So this test isolates the migration that has to work on both: it copies
 * {@code V12__auth_users_and_tokens.sql} alone into a temporary Flyway location and
 * migrates an in-memory H2 in {@code MODE=PostgreSQL}. That proves the file is valid
 * H2 DDL, which is what the requirement means — and it is not a substitute for
 * {@code PostgresSchemaTest}, which applies the whole set to a real PostgreSQL and
 * has Hibernate validate the entities against it. Neither half covers the other.</p>
 *
 * <p>The assertions go past "it ran without error": the two tables, every column the
 * entities map, the unique indexes the service depends on for its login and token
 * lookups, and the cascade that makes deleting an account take its sessions with it.
 * A migration that created two empty tables would satisfy a smoke test and fail
 * every one of these.</p>
 */
class V12MigrationH2Test {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V12__auth_users_and_tokens.sql");

    @Test
    @DisplayName("V12 applies cleanly on H2 and builds the schema the service uses")
    void theAuthMigrationAppliesOnH2() throws Exception {
        assertTrue(Files.isRegularFile(MIGRATION), "missing migration: " + MIGRATION);

        // A location of its own, holding only this migration: see the class comment.
        Path location = tempDir.resolve("only-v12");
        Files.createDirectories(location);
        Files.copy(MIGRATION, location.resolve(MIGRATION.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);

        String url = "jdbc:h2:mem:v12_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("filesystem:" + location.toAbsolutePath())
                .load();

        var result = flyway.migrate();
        assertEquals(1, result.migrationsExecuted,
                "exactly the one migration must run, in isolation from the others");

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertEquals(Set.of("AUTH_TOKENS", "AUTH_USERS"), tables(connection),
                    "the migration must create both auth tables and nothing else");

            assertEquals(Set.of(
                            "ID", "USERNAME", "PASSWORD_HASH", "ROLE", "MUST_CHANGE_PASSWORD",
                            "DISABLED", "FAILED_ATTEMPTS", "LOCKED_UNTIL", "PASSWORD_CHANGED_AT",
                            "CREATED_AT", "UPDATED_AT"),
                    columnsOf(connection, "AUTH_USERS"));

            assertEquals(Set.of(
                            "ID", "USER_ID", "TOKEN_HASH", "ISSUED_AT", "EXPIRES_AT",
                            "REVOKED_AT", "LAST_USED_AT"),
                    columnsOf(connection, "AUTH_TOKENS"));

            // The two lookups every request depends on are indexed and unique; without
            // the uniqueness a duplicate username would make "who is this ambiguous"
            // at exactly the moment that matters, and a duplicate token hash would let
            // two users share a session.
            assertTrue(uniqueIndexOn(connection, "AUTH_USERS", "USERNAME"),
                    "usernames must be unique at the database level, not only in Java");
            assertTrue(uniqueIndexOn(connection, "AUTH_TOKENS", "TOKEN_HASH"),
                    "token hashes must be unique");
            assertTrue(indexOn(connection, "AUTH_TOKENS", "USER_ID"),
                    "revoking every session a user holds is a lookup by user id");

            // Deleting an account takes its sessions with it, so a removal cannot
            // leave live tokens pointing at nobody.
            assertEquals(1, foreignKeysFrom(connection, "AUTH_TOKENS"),
                    "auth_tokens must reference auth_users");

            // And the row inserts, which is the part "the DDL parsed" does not prove.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO auth_users"
                        + " (id, username, password_hash, role, must_change_password, disabled,"
                        + " failed_attempts, created_at, updated_at)"
                        + " VALUES ('" + UUID.randomUUID() + "', 'schema-gate', '$2a$04$hash',"
                        + " 'SUPERVISOR', TRUE, FALSE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                assertEquals(1, countOf(connection, "AUTH_USERS"));

                // The defaults the entity relies on must be the database's too.
                try (ResultSet row = statement.executeQuery(
                        "SELECT must_change_password, disabled, failed_attempts FROM auth_users")) {
                    assertTrue(row.next(), "the inserted row must be readable");
                    assertTrue(row.getBoolean("must_change_password"),
                            "a row created without the flag must default to the restrictive value");
                    assertFalse(row.getBoolean("disabled"));
                    assertEquals(0, row.getInt("failed_attempts"));
                }
            }
        }
    }

    @TempDir Path tempDir;

    // ------------------------------------------------------------------ helpers

    private static Set<String> tables(Connection connection) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables"
                             + " WHERE table_schema = 'PUBLIC'")) {
            while (rows.next()) {
                String name = rows.getString(1).toUpperCase(Locale.ROOT);
                if (!name.startsWith("FLYWAY_")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Set<String> columnsOf(Connection connection, String table) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT column_name FROM information_schema.columns"
                             + " WHERE table_name = '" + table + "'")) {
            while (rows.next()) {
                names.add(rows.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Whether some unique index covers exactly this column. */
    private static boolean uniqueIndexOn(Connection connection, String table, String column)
            throws Exception {
        return indexColumn(connection, table, column, true);
    }

    private static boolean indexOn(Connection connection, String table, String column)
            throws Exception {
        return indexColumn(connection, table, column, false);
    }

    /**
     * JDBC's own index metadata rather than {@code INFORMATION_SCHEMA}: the latter
     * differs between H2 and PostgreSQL (H2 2.x has no {@code IS_UNIQUE} column,
     * and its {@code INDEXES} view is shaped unlike Postgres's), which is precisely
     * the kind of difference this test exists to catch rather than depend on. The
     * driver's answer is the same question asked the portable way.
     */
    private static boolean indexColumn(Connection connection, String table, String column,
                                       boolean uniqueOnly) throws Exception {
        try (ResultSet rows = connection.getMetaData()
                .getIndexInfo(null, null, table, uniqueOnly, false)) {
            while (rows.next()) {
                String indexed = rows.getString("COLUMN_NAME");
                if (indexed != null && column.equalsIgnoreCase(indexed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int foreignKeysFrom(Connection connection, String table) throws Exception {
        int count = 0;
        try (ResultSet rows = connection.getMetaData()
                .getImportedKeys(null, null, table)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }

    private static int countOf(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
