package io.mosip.liveness.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment, checked in both directions.
 *
 * <p>A deployment's environment is a contract, and a contract with a stale
 * template is worse than no template: {@code .env.example} is what an operator
 * copies, so a variable it omits is one nobody sets (the service then runs on a
 * default they never chose), and a variable it invents is one they will set
 * expecting it to do something. {@code APP_ENV} and {@code LOG_LEVEL} were in
 * this repository's template for exactly that reason — they belong to the
 * Python backend in {@code pad_liveness_backend/}, not to this service.</p>
 *
 * <p>Three rules, each about a different way the list can rot:</p>
 * <ol>
 *   <li>every variable the service reads is in the template;</li>
 *   <li>every variable in the template is read by the service (directly, via
 *       {@code System.getenv}, or through a Spring placeholder);</li>
 *   <li>a variable the template marks as a secret ships with no value, so a
 *       copied file cannot put a real key in a real deployment.</li>
 * </ol>
 */
class EnvironmentVariableInventoryTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path TEMPLATE = PROJECT_ROOT.resolve(".env.example");
    private static final Path RESOURCES = PROJECT_ROOT.resolve("src/main/resources");
    private static final Path MAIN_JAVA = PROJECT_ROOT.resolve("src/main/java");

    /** Spring placeholder: {@code ${NAME}} or {@code ${NAME:default}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

    /** A direct read: {@code System.getenv("NAME")}. */
    private static final Pattern GETENV = Pattern.compile("System\\.getenv\\(\"([A-Z][A-Z0-9_]*)\"\\)");

    /** A template line naming a variable, commented out or not. */
    private static final Pattern TEMPLATE_KEY = Pattern.compile("^#?\\s*([A-Z][A-Z0-9_]*)=");

    /** A template line that sets a variable (not commented out). */
    private static final Pattern TEMPLATE_VALUE = Pattern.compile("^([A-Z][A-Z0-9_]*)=(.*)$");

    /** Marks the line above a value as a secret. */
    private static final String SECRET_MARKER = "# SECRET";

    /**
     * The secrets, named here so nobody can quietly un-mark one.
     *
     * <p>{@code POSTGRES_APP_PASSWORD} joined this list with the
     * least-privilege role: the service connects as {@code pad_runtime} rather than
     * as the cluster's bootstrap superuser, and that role's password is a
     * secret in its own right. It is a separate entry rather than a rename
     * because {@code POSTGRES_PASSWORD} is still read — as the fallback for a
     * deployment given a single credential pair.</p>
     *
     * <p>{@code MOSIP_OPERATOR_API_KEY} is a shared key for the smaller role:
     * whoever holds it can read the operator surface. It is marked here for the
     * same reason — a copied template must not carry a value.</p>
     *
     * <p>{@code MOSIP_AUTH_BOOTSTRAP_PASSWORD} creates the first supervisor on an
     * empty database, so it is a credential like any other: the template ships it
     * blank and commented out, and a deployment that copies the file cannot
     * arrive with a known one. {@code MOSIP_ADMIN_API_KEY} left this list with the
     * key itself, which the accounts and sessions in
     * V12__auth_users_and_tokens.sql replaced.</p>
     */
    private static final Set<String> SECRETS = Set.of(
            "POSTGRES_PASSWORD",
            "POSTGRES_APP_PASSWORD",
            "MOSIP_OPERATOR_API_KEY",
            "MOSIP_AUTH_BOOTSTRAP_PASSWORD",
            "MOSIP_AUDIT_HMAC_SECRET",
            "MOSIP_AUDIT_HMAC_PREVIOUS_SECRET");

    @Test
    void everyVariableTheServiceReadsIsInTheTemplate() {
        Set<String> read = new TreeSet<>(configuredVariables());
        read.addAll(directlyReadVariables());
        Set<String> documented = new TreeSet<>(templateVariables().keySet());

        Set<String> undocumented = new TreeSet<>(read);
        undocumented.removeAll(documented);
        assertTrue(undocumented.isEmpty(),
                "Variable(s) the service reads but .env.example never mentions: " + undocumented
                        + "\nAdd each to .env.example (with its default and, if it is a key, a "
                        + "# SECRET marker above it).");
    }

    @Test
    void everyTemplateVariableIsReadByTheService() {
        Set<String> read = new TreeSet<>(configuredVariables());
        read.addAll(directlyReadVariables());
        Set<String> documented = new TreeSet<>(templateVariables().keySet());

        Set<String> dead = new TreeSet<>(documented);
        dead.removeAll(read);
        assertTrue(dead.isEmpty(),
                "Variable(s) in .env.example that nothing reads: " + dead
                        + "\nRemove them, or wire them up — an operator who sets one of these "
                        + "believes it changed something.");
        assertFalse(read.isEmpty(), "Parsing found no variables at all — check the paths this test reads.");
    }

    @Test
    void secretVariablesShipWithNoValue() {
        Map<String, String> values = templateVariables();
        assertEquals(SECRETS, new TreeSet<>(secretMarkedVariables()),
                "The set of variables marked '# SECRET' in .env.example changed; a new secret "
                        + "must be marked, and this list is what keeps one from being un-marked.");
        for (String secret : SECRETS) {
            if (!values.containsKey(secret)) {
                continue; // commented out entirely, which also ships no value
            }
            assertEquals("", values.get(secret),
                    secret + " must ship no value in .env.example — a copied template would "
                            + "otherwise carry a key into a deployment.");
        }
    }

    @Test
    void envFileIsIgnoredButTheTemplateIsNot() {
        String ignores = read(TEMPLATE.getParent().resolve(".gitignore"));
        // docker compose reads ./.env automatically, so grep the directive and
        // not the comment above it.
        assertTrue(ignores.lines().anyMatch(line -> line.trim().equals(".env")),
                ".gitignore must ignore .env — docker compose reads it automatically, so real keys "
                        + "land in a file that is one `git add .` away from the history.");
        assertTrue(ignores.lines().anyMatch(line -> line.trim().equals("!.env.example")),
                ".gitignore must un-ignore .env.example — the template has to stay tracked.");
    }

    /** Names bound by Spring placeholders in every {@code *.yml} under src/main/resources. */
    private static Set<String> configuredVariables() {
        Set<String> names = new TreeSet<>();
        for (Path yml : listFiles(RESOURCES, ".yml")) {
            Matcher matcher = PLACEHOLDER.matcher(read(yml));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Names read straight from the environment. Scanned rather than listed so a
     * new {@code System.getenv} cannot slip past the template rule.
     */
    private static Set<String> directlyReadVariables() {
        Set<String> names = new TreeSet<>();
        for (Path java : listFiles(MAIN_JAVA, ".java")) {
            Matcher matcher = GETENV.matcher(read(java));
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** Every variable named in the template, mapped to the value it ships. */
    private static Map<String, String> templateVariables() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : read(TEMPLATE).lines().toList()) {
            Matcher key = TEMPLATE_KEY.matcher(line);
            if (key.find()) {
                // A commented-out variable ships no value; only record one that
                // is actually set. Nested patterns keep the group indexes honest.
                Matcher value = TEMPLATE_VALUE.matcher(line);
                values.put(key.group(1), value.find() ? value.group(2).trim() : "");
            }
        }
        return values;
    }

    /**
     * Variables the template marks as secrets. The marker applies to the single
     * line below it, so a block of commented-out variables cannot inherit the
     * mark from one above them.
     */
    private static Set<String> secretMarkedVariables() {
        Set<String> marked = new LinkedHashSet<>();
        boolean markedNext = false;
        for (String line : read(TEMPLATE).lines().toList()) {
            if (markedNext) {
                Matcher key = TEMPLATE_KEY.matcher(line);
                if (key.find()) {
                    marked.add(key.group(1));
                }
                markedNext = false;
                continue;
            }
            if (line.trim().startsWith(SECRET_MARKER)) {
                markedNext = true;
            }
        }
        return marked;
    }

    /** Files under {@code dir} (recursively) with the given suffix, sorted. */
    private static List<Path> listFiles(Path dir, String suffix) {
        assertTrue(Files.isDirectory(dir), "Not a directory: " + dir
                + " — this test resolves paths from the module directory.");
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + dir, e);
        }
    }

    private static String read(Path path) {
        assertTrue(Files.isRegularFile(path), "Missing file: " + path);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
