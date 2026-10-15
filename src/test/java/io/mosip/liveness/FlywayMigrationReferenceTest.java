package io.mosip.liveness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration-reference gate: fails the build when a comment, document or
 * script cites a Flyway migration that does not exist under
 * {@code src/main/resources/db/migration}.
 *
 * <p>This exists because migration files get renamed and renumbered, and the
 * references to them do not travel with the file. The drift that motivated
 * this gate: three files described the audit-chain hash columns and
 * immutability triggers as living in a migration this repository never had —
 * the file has been {@code V9__audit_chain_immutability.sql} since it landed,
 * but two javadoc comments and a test comment still cited the old number.
 * Every compiler and every test stayed green; only a human comparing the
 * migration directory against the comments could see it.</p>
 *
 * <p>The gate is self-maintaining. It discovers the migration set from the
 * directory at runtime instead of pinning a list — the same lesson the
 * PostgreSQL schema gate learned when its assertion was hardcoded to a
 * version that later got renumbered — so adding the next migration needs no
 * edit here. Renaming or renumbering one turns the gate red wherever the old
 * identity is still cited, which is the point.</p>
 *
 * <p>Three citation shapes are understood:</p>
 * <ol>
 *   <li><b>Filename</b> — {@code V9__audit_chain_immutability.sql}. The exact
 *       file must exist, so a renamed description fails even when the version
 *       number is still live.</li>
 *   <li><b>Javadoc code span</b> — {@code V9}. Always treated as a migration
 *       citation: the original drift used exactly this shape, with no
 *       migration vocabulary anywhere near it for a window heuristic to see.</li>
 *   <li><b>Prose version</b> — "before {@code V9}", "the {@code V4} seed",
 *       range tails. Checked only when migration vocabulary (migration,
 *       Flyway) or a filename citation sits on the same line or within two
 *       lines, which keeps unrelated version tokens out of scope. Matching is
 *       uppercase-only on purpose: lowercase v-numbers are tool versions
 *       ({@code actions/checkout@v4}, {@code minifasnet-v2}) and would drown
 *       the gate in noise.</li>
 * </ol>
 *
 * <p><b>Rule two — spec section anchors.</b> The two liveness specs are
 * checked in under {@code docs/references/} ({@code orchestration.auth} and
 * {@code analyse.md}) and the repository cites their sections as anchors.
 * File-qualified citations such as "orchestration.auth §5.2" or
 * "analyse.md §5.4" are validated against that spec's own heading set, which
 * is extracted from the checked-in file at test time — renumber a spec
 * section and the gate turns red wherever the old number is still cited.
 * Bare anchors such as "§4" are validated against the union of both specs,
 * but only where the citation is framed as a spec one: the standalone word
 * "spec" on the line, a mention of either spec filename, or any markdown file
 * under {@code android_client/}, which cites the orchestration spec by bare
 * anchor. Doc-internal anchors are deliberately out of scope: this repo's
 * design, guide and resource-compliance documents have their own numbered
 * sections, and those citations do not travel with the specs.</p>
 *
 * <p><b>Rule three — config key citations.</b> Backend configuration is
 * cited as dotted paths under {@code mosip.liveness.} — for example
 * {@code mosip.liveness.diagnostics-enabled} — in docs, comments, code
 * strings and annotations alike. Every citation must name a key that
 * {@code application.yml} declares, or that a config class declares with
 * {@code @Value}; a service's own {@code @Value} is a citation, not a
 * definition, which keeps every key declared in one of those two places. The
 * one namespace drawn from elsewhere is {@code mosip.liveness.android.*}:
 * its build-flag names are declared as quoted string constants in the
 * Android gate-policy package and never appear in Spring configuration, so
 * that package is their definition site. Citing a namespace of declared keys
 * passes; citing a leaf nothing declares fails. The key set is parsed from
 * application.yml at test time, so renaming or removing a key turns the gate
 * red wherever the old name is still cited, and adding one needs no edit
 * here.</p>
 *
 * <p>Scanned: every owned text file in the repository — sources, resources,
 * docs, scripts, build files — except third-party/vendored trees, build
 * output, dot-directories and {@code *.backup} working notes. All three
 * detectors are validated in-file: the second test plants dangling citations
 * of each shape (built by string concatenation, so this source file never
 * contains a literal dangling token that the scanner would trip over) and
 * asserts they are caught.</p>
 */
class FlywayMigrationReferenceTest {

    /** Exact filename citation: {@code V9__audit_chain_immutability.sql}. */
    private static final Pattern FILE_REF =
            Pattern.compile("\\bV(\\d+)__([A-Za-z0-9_]+)\\.sql\\b");

    /** Javadoc code-span citation: {@code V9}. Checked unconditionally. */
    private static final Pattern CODE_SPAN = Pattern.compile("\\{@code V(\\d+)\\}");

    /**
     * Bare version token: "before {@code V9}", "the {@code V4} seed", the tail
     * of a range. Deliberately uppercase-only; the lookarounds keep it from
     * re-matching filename citations and from gluing onto identifiers.
     */
    private static final Pattern BARE =
            Pattern.compile("(?<![A-Za-z0-9_.])V(\\d+)(?![A-Za-z0-9_])");

    /** Vocabulary that marks a line's neighbourhood as talking about migrations. */
    private static final Pattern MIGRATION_VOCAB = Pattern.compile("(?i)\\b(migrations?|flyway)\\b");

    private static final String MIGRATION_DIR = "src/main/resources/db/migration";

    // ------------------------------------------------- spec anchor rule (2)

    /** Checked-in spec: numbered top sections plus dashed subsections. */
    private static final String ORCHESTRATION_SPEC = "docs/references/orchestration.auth";

    /** Checked-in spec: markdown headings (top level dotted, subsections not). */
    private static final String ANALYSE_SPEC = "docs/references/analyse.md";

    /** File-qualified anchor: {@code orchestration.auth §5.2}, {@code analyse.md §6.4}. */
    private static final Pattern FILE_QUALIFIED_ANCHOR =
            Pattern.compile("(orchestration\\.auth|analyse\\.md)\\s*§\\s*(\\d+(?:\\.\\d+)?)",
                    Pattern.CASE_INSENSITIVE);

    /** Anchor shape: {@code §5.2}. Checked only where a line frames it as a spec citation. */
    private static final Pattern BARE_ANCHOR = Pattern.compile("§\\s*(\\d+(?:\\.\\d+)?)");

    /** A standalone "spec" word marking the line's anchors as spec citations. */
    private static final Pattern SPEC_FRAME = Pattern.compile("(?<![-\\w])spec\\b", Pattern.CASE_INSENSITIVE);

    /** orchestration.auth headings: {@code 5. SEQUENCE DIAGRAMS} / {@code --- 5.2 ... ---}. */
    private static final Pattern ORCHESTRATION_TOP_HEADING = Pattern.compile("^(\\d{1,2})\\.\\s");
    private static final Pattern ORCHESTRATION_SUB_HEADING = Pattern.compile("^---\\s(\\d+\\.\\d+)\\s");

    /** analyse.md headings: {@code ## 5. Title} has the dot, {@code ### 5.4 Title} does not. */
    private static final Pattern ANALYSE_HEADING = Pattern.compile("^#{2,3}\\s+(\\d+(?:\\.\\d+)?)(?:\\.|\\s)");

    // ---------------------------------------------- config key rule (3)

    /** The Spring configuration namespace this rule governs. */
    private static final String CONFIG_ROOT = "mosip.liveness";

    /** The documented source of truth for backend config keys. */
    private static final String CONFIG_YML = "src/main/resources/application.yml";

    /** The config classes: their {@code @Value} declarations define keys too. */
    private static final Set<String> CONFIG_CLASS_DIRS = Set.of(
            "src/main/java/io/mosip/liveness/config",
            "src/main/java/io/mosip/liveness/app/config");

    /**
     * The Android gate-policy package: the {@code mosip.liveness.android.*}
     * build-flag names are declared here as quoted string constants and never
     * in Spring configuration, so this package is their definition site.
     */
    private static final String ANDROID_FLAG_DIR = "src/main/java/io/mosip/liveness/android";

    /**
     * Key citation: a dotted {@code mosip.liveness.<key>} path. The lookbehind
     * keeps package names ({@code io.mosip.liveness.api}) out of scope;
     * kebab-case segments keep a sentence-final period from swallowing the
     * next word into the key.
     */
    private static final Pattern CONFIG_KEY =
            Pattern.compile("(?<![\\w.])mosip\\.liveness\\.[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*");

    /** Declaration in a config class: {@code @Value} reading {@code mosip.liveness.<key>}. */
    private static final Pattern CONFIG_VALUE_DECL = Pattern.compile(
            "@Value\\(\"\\$\\{(mosip\\.liveness\\.[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*)(?=[:}])");

    /** Declaration of a build flag: a quoted full-key literal in the android package. */
    private static final Pattern ANDROID_FLAG_DECL = Pattern.compile(
            "\\\"(mosip\\.liveness\\.android\\.[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*)\\\"");

    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            ".java", ".md", ".sql", ".yml", ".yaml", ".properties", ".xml", ".py",
            ".sh", ".dart", ".ts", ".mjs", ".js", ".kts", ".gradle", ".toml",
            ".html", ".txt", ".cfg", ".json");

    /** Third-party or generated trees whose text this repository does not own. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "vendor", "target", "node_modules", "release-assets", "dist", "build");

    private static final long MAX_FILE_BYTES = 2_000_000;

    @Test
    @DisplayName("every cited Flyway migration exists in db/migration")
    void everyReferencedMigrationExists() throws IOException {
        Path root = repoRoot();
        TreeMap<Integer, String> migrations = discoverMigrations(root);
        assertTrue(migrations.size() >= 3,
                "expected to discover the real migration set under " + MIGRATION_DIR
                        + " — found " + migrations.size() + "; is the directory still there?");

        List<String> namingProblems = migrationNamingProblems(root);
        assertTrue(namingProblems.isEmpty(),
                "files in " + MIGRATION_DIR + " that are not V<n>__<name>.sql: " + namingProblems);

        List<String> violations = new ArrayList<>();
        int scanned = scanOwnedFiles(root, (path, text) ->
                violations.addAll(findViolations(label(root, path), text, migrations)));

        assertTrue(scanned >= 50,
                "the scan saw only " + scanned + " files — the walker is no longer reaching "
                        + "the owned trees, so this gate would pass vacuously");

        assertTrue(violations.isEmpty(),
                "citations to migrations that do not exist (known: " + knownVersions(migrations) + "):\n"
                        + String.join("\n", violations)
                        + "\nUpdate the citation to the renumbered migration, or restore the file.");
    }

    @Test
    @DisplayName("the detectors catch planted dangling citations and pass live ones")
    void detectorIsSelfValidating() throws IOException {
        TreeMap<Integer, String> migrations = discoverMigrations(repoRoot());
        int highest = migrations.lastKey();

        // Built by concatenation so this source file never contains a literal
        // token that the scanner — which reads this very file — would flag.
        int absent = highest + 10;
        String badFile = "V" + absent + "__does_not_exist.sql";
        String badSpan = "{@code V" + absent + "}";
        String badProse = "written before V" + absent + " have no hash";
        String staleDescription = "V" + highest + "__description_renamed_away.sql";
        String liveFile = "V" + highest + "__" + suffixOf(migrations.lastEntry().getValue()) + ".sql";

        // Filename and code-span citations are checked unconditionally.
        assertFalse(findViolations("see " + badFile, migrations).isEmpty(),
                "a dangling filename citation must be flagged");
        assertFalse(findViolations("see " + badSpan, migrations).isEmpty(),
                "a dangling code-span citation must be flagged");
        // A live version cited under a description that no longer exists fails too.
        assertFalse(findViolations("see " + staleDescription, migrations).isEmpty(),
                "a stale filename description must be flagged even for a live version");

        // A prose citation is caught when migration vocabulary is nearby — on
        // the same line or within the two-line window.
        assertFalse(findViolations("the migration history: entries " + badProse, migrations).isEmpty(),
                "a prose citation with vocabulary on the same line must be flagged");
        assertFalse(findViolations("about migrations\nentries " + badProse, migrations).isEmpty(),
                "a prose citation with vocabulary one line above must be flagged");

        // Live citations and unrelated version-shaped tokens produce nothing.
        assertTrue(findViolations("see " + liveFile, migrations).isEmpty(),
                "a live filename citation must pass");
        assertTrue(findViolations("as added in {@code V" + highest + "}", migrations).isEmpty(),
                "a live code-span citation must pass");
        assertTrue(findViolations("backed by MiniFASNet-V" + 2 + " here", migrations).isEmpty(),
                "a version-shaped model name without migration vocabulary must be ignored");

        // The spec-anchor detector: same construction trick — the dangling
        // token only exists at runtime, so the scan of this file never sees it.
        Map<String, Set<String>> anchors = loadSpecAnchors(repoRoot());
        String absentAnchor = "99" + ".9";
        assertFalse(findAnchorViolations("planted",
                "see orchestration.auth §" + absentAnchor, anchors).isEmpty(),
                "a file-qualified anchor that is not a heading of that spec must be flagged");
        assertFalse(findAnchorViolations("planted",
                "as cited by the spec §" + absentAnchor, anchors).isEmpty(),
                "a bare anchor on a spec-framed line that neither spec has must be flagged");
        assertFalse(findAnchorViolations("android_client/x.md",
                "flow step §" + absentAnchor, anchors).isEmpty(),
                "every bare anchor in android_client markdown is a spec citation");

        assertTrue(findAnchorViolations("live", "see orchestration.auth §5.2", anchors).isEmpty(),
                "a file-qualified anchor matching a real heading must pass");
        assertTrue(findAnchorViolations("live", "the spec §5.4 resolved here", anchors).isEmpty(),
                "a bare anchor present in the union of both specs must pass");
        assertTrue(findAnchorViolations("live",
                "design §" + absentAnchor + " cites this repo's own document, which the rule ignores",
                anchors).isEmpty(),
                "an unframed doc-internal anchor is out of scope even when dangling");

        // The config-key detector: same construction trick — the dangling key
        // only exists at runtime, so the scan of this file never sees it.
        Set<String> definedKeys = discoverConfigKeys(repoRoot());
        String absentKey = "ghost-key";
        assertFalse(findConfigKeyViolations("planted",
                "set mosip.liveness." + absentKey + " in the docs", definedKeys).isEmpty(),
                "a cited key that neither application.yml nor a config class defines must be flagged");

        assertTrue(findConfigKeyViolations("live", "set mosip.liveness.backend here", definedKeys).isEmpty(),
                "a key declared in application.yml must pass");
        assertTrue(findConfigKeyViolations("live", "package io.mosip.liveness.api in code", definedKeys).isEmpty(),
                "a package name is not a config-key citation");
        assertTrue(findConfigKeyViolations("live", "group mosip.liveness.android", definedKeys).isEmpty(),
                "citing the build-flag namespace itself must pass as a prefix of a declared flag");
        assertTrue(findConfigKeyViolations("live", "flag mosip.liveness.android.allow-disable", definedKeys).isEmpty(),
                "the build flag declared in the android package must pass");
    }

    @Test
    @DisplayName("every cited spec section anchor resolves to a checked-in spec heading")
    void everySpecSectionAnchorResolves() throws IOException {
        Path root = repoRoot();
        Map<String, Set<String>> anchors = loadSpecAnchors(root);

        List<String> violations = new ArrayList<>();
        int scanned = scanOwnedFiles(root, (path, text) ->
                violations.addAll(findAnchorViolations(label(root, path), text, anchors)));

        assertTrue(scanned >= 50,
                "the scan saw only " + scanned + " files — the walker no longer reaches the owned "
                        + "trees, so this rule would pass vacuously");

        assertTrue(violations.isEmpty(),
                "cited spec section anchors that match no heading in the checked-in specs:\n"
                        + String.join("\n", violations)
                        + "\nUpdate the citation to the section that exists, or restore the heading "
                        + "in docs/references/.");
    }

    @Test
    @DisplayName("every cited mosip.liveness config key is defined")
    void everyCitedConfigKeyIsDefined() throws IOException {
        Path root = repoRoot();
        Set<String> defined = discoverConfigKeys(root);

        List<String> violations = new ArrayList<>();
        int scanned = scanOwnedFiles(root, (path, text) ->
                violations.addAll(findConfigKeyViolations(label(root, path), text, defined)));

        assertTrue(scanned >= 50,
                "the scan saw only " + scanned + " files — the walker no longer reaches the owned "
                        + "trees, so this rule would pass vacuously");

        assertTrue(violations.isEmpty(),
                "cited config keys that " + CONFIG_YML + " and the config classes do not define:\n"
                        + String.join("\n", violations)
                        + "\nDeclare the key where the gate can discover it (application.yml, a "
                        + "config class's @Value, or an android build-flag constant), or fix the citation.");
    }

    // ------------------------------------------------------------- discovery

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(MIGRATION_DIR))) {
                return dir;
            }
        }
        throw new IllegalStateException("could not locate " + MIGRATION_DIR
                + " at or above " + Paths.get("").toAbsolutePath());
    }

    /** version → filename for every {@code V<n>__<name>.sql} in the migration dir. */
    private static TreeMap<Integer, String> discoverMigrations(Path root) throws IOException {
        TreeMap<Integer, String> migrations = new TreeMap<>();
        List<Path> files;
        try (var stream = Files.list(root.resolve(MIGRATION_DIR))) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            Matcher m = FILE_REF.matcher(name);
            if (m.matches()) {
                migrations.putIfAbsent(Integer.parseInt(m.group(1)), name);
            }
        }
        return migrations;
    }

    private static List<String> migrationNamingProblems(Path root) throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.list(root.resolve(MIGRATION_DIR))) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.endsWith(".sql") && !FILE_REF.matcher(name).matches()) {
                problems.add(name);
            }
        }
        return problems;
    }

    // -------------------------------------------------------------- scanning

    private static int scanOwnedFiles(Path root, BiConsumer<Path, String> visitor) throws IOException {
        int[] scanned = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                // Dot-directories (.git, .mvn, .github, .idea, ...) hold tool
                // config, not citations; the named trees are not ours to own.
                if (EXCLUDED_DIRS.contains(name) || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".backup")
                        || SCANNED_EXTENSIONS.stream().noneMatch(name::endsWith)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    visitor.accept(file, new String(Files.readAllBytes(file),
                            StandardCharsets.UTF_8));
                    scanned[0]++;
                } catch (IOException ignored) {
                    // An unreadable file is an environment problem, not citation drift.
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return scanned[0];
    }

    private static String label(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------- detection

    private static List<String> findViolations(String text, TreeMap<Integer, String> migrations) {
        return findViolations(null, text, migrations);
    }

    private static List<String> findViolations(String label, String text,
                                               TreeMap<Integer, String> migrations) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String where = (label == null ? "line " + (i + 1) : label + ":" + (i + 1));

            Matcher m = FILE_REF.matcher(lines[i]);
            while (m.find()) {
                int version = Integer.parseInt(m.group(1));
                String filename = "V" + version + "__" + m.group(2) + ".sql";
                if (!migrations.containsValue(filename)) {
                    found.add(where + ": cites " + filename + " — no such file (known: "
                            + knownVersions(migrations) + ")");
                }
            }

            m = CODE_SPAN.matcher(lines[i]);
            while (m.find()) {
                int version = Integer.parseInt(m.group(1));
                if (!migrations.containsKey(version)) {
                    found.add(where + ": cites {@code V" + version + "} — no migration V"
                            + version + " exists (known: " + knownVersions(migrations) + ")");
                }
            }

            if (nearMigrationVocabulary(lines, i)) {
                m = BARE.matcher(lines[i]);
                while (m.find()) {
                    int version = Integer.parseInt(m.group(1));
                    if (!migrations.containsKey(version)) {
                        found.add(where + ": cites V" + version + " — no migration V"
                                + version + " exists (known: " + knownVersions(migrations) + ")");
                    }
                }
            }
        }
        return found;
    }

    private static boolean nearMigrationVocabulary(String[] lines, int index) {
        int first = Math.max(0, index - 2);
        int last = Math.min(lines.length - 1, index + 2);
        for (int i = first; i <= last; i++) {
            if (MIGRATION_VOCAB.matcher(lines[i]).find() || FILE_REF.matcher(lines[i]).find()) {
                return true;
            }
        }
        return false;
    }

    private static String knownVersions(TreeMap<Integer, String> migrations) {
        return migrations.keySet().stream().map(v -> "V" + v).collect(Collectors.joining(", "));
    }

    private static String suffixOf(String filename) {
        return filename.replaceAll("^V\\d+__", "").replaceAll("\\.sql$", "");
    }

    // -------------------------------------------------------- spec discovery

    /** section anchors per spec filename, extracted from the checked-in files. */
    private static Map<String, Set<String>> loadSpecAnchors(Path root) throws IOException {
        Map<String, Set<String>> anchors = new LinkedHashMap<>();
        anchors.put("orchestration.auth",
                specAnchors(root, ORCHESTRATION_SPEC,
                        ORCHESTRATION_TOP_HEADING, ORCHESTRATION_SUB_HEADING));
        anchors.put("analyse.md", specAnchors(root, ANALYSE_SPEC, ANALYSE_HEADING));
        return anchors;
    }

    /**
     * The heading set of one checked-in spec. The file's presence is part of
     * the contract: if it is missing the rule must fail loudly rather than
     * validate citations against nothing.
     */
    private static Set<String> specAnchors(Path root, String relative, Pattern... headings) throws IOException {
        Path spec = root.resolve(relative);
        assertTrue(Files.isRegularFile(spec),
                relative + " must be checked in — this rule validates cited section anchors "
                        + "against its headings and must not pass with the spec absent");
        String text = new String(Files.readAllBytes(spec), StandardCharsets.UTF_8);
        assertFalse(text.isBlank(), relative + " is empty — there are no headings to validate against");

        Set<String> anchors = new TreeSet<>();
        for (String line : text.split("\n", -1)) {
            for (Pattern heading : headings) {
                Matcher m = heading.matcher(line);
                if (m.find()) {
                    anchors.add(m.group(1));
                }
            }
        }
        assertTrue(anchors.size() >= 10,
                "extracted only " + anchors.size() + " section headings from " + relative
                        + " — the heading format changed? the rule would pass vacuously");
        return anchors;
    }

    // ---------------------------------------------------------- anchor rule

    private static List<String> findAnchorViolations(String label, String text,
                                                     Map<String, Set<String>> specAnchors) {
        List<String> found = new ArrayList<>();
        Set<String> union = new TreeSet<>();
        specAnchors.values().forEach(union::addAll);

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String where = (label == null ? "line " + (i + 1) : label + ":" + (i + 1));
            String line = lines[i];

            // File-qualified citations are always checked, against that spec alone.
            List<int[]> qualified = new ArrayList<>();
            Matcher m = FILE_QUALIFIED_ANCHOR.matcher(line);
            while (m.find()) {
                String spec = m.group(1).toLowerCase(Locale.ROOT);
                qualified.add(new int[] {m.start(2), m.end(2)});
                Set<String> headings = specAnchors.get(spec);
                if (headings == null || !headings.contains(m.group(2))) {
                    found.add(where + ": cites " + m.group(0) + " — " + spec
                            + " has no section " + m.group(2) + " (headings: "
                            + knownAnchors(headings) + ")");
                }
            }

            // Bare anchors count as spec citations only on a framed line.
            String lower = line.toLowerCase(Locale.ROOT);
            boolean frame = SPEC_FRAME.matcher(line).find()
                    || lower.contains("orchestration.auth")
                    || lower.contains("analyse.md");
            boolean fileScoped = label != null && label.startsWith("android_client/")
                    && label.endsWith(".md");
            if (!frame && !fileScoped) {
                continue;
            }

            m = BARE_ANCHOR.matcher(line);
            while (m.find()) {
                int start = m.start(1);
                int end = m.end(1);
                boolean alreadyChecked = qualified.stream().anyMatch(r -> r[0] <= start && end <= r[1]);
                if (alreadyChecked) {
                    continue;
                }
                if (!union.contains(m.group(1))) {
                    found.add(where + ": cites §" + m.group(1)
                            + " on a spec-framed line — neither spec has that section (known: "
                            + knownAnchors(union) + ")");
                }
            }
        }
        return found;
    }

    /** anchors sorted by numeric section order, for readable failure messages. */
    private static String knownAnchors(Set<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return "none";
        }
        return anchors.stream()
                .sorted(Comparator.comparingInt((String a) -> Integer.parseInt(a.split("\\.")[0]))
                        .thenComparingInt(a -> a.contains(".")
                                ? Integer.parseInt(a.substring(a.indexOf('.') + 1)) : -1))
                .collect(Collectors.joining(", "));
    }

    // -------------------------------------------------------- config key rule

    /**
     * The keys {@code application.yml} and the config classes define — parsed,
     * not pinned: renaming or dropping a key turns the gate red wherever the
     * old name is still cited, and adding one needs no edit here.
     */
    private static Set<String> discoverConfigKeys(Path root) throws IOException {
        Set<String> defined = new TreeSet<>();

        // application.yml: every node path under the mosip.liveness subtree.
        Path yml = root.resolve(CONFIG_YML);
        assertTrue(Files.isRegularFile(yml),
                CONFIG_YML + " is missing — the config-key rule reads the key set from it");
        try (InputStream in = Files.newInputStream(yml)) {
            Object liveness = field(field(new Yaml().load(in), "mosip"), "liveness");
            assertTrue(liveness instanceof Map<?, ?>,
                    CONFIG_YML + " no longer defines a " + CONFIG_ROOT
                            + " subtree — the rule cannot validate citations without it");
            collectConfigPaths(liveness, CONFIG_ROOT, defined);
        }

        // Config classes: @Value declarations are definitions too.
        for (String dir : CONFIG_CLASS_DIRS) {
            Path classes = root.resolve(dir);
            assertTrue(Files.isDirectory(classes),
                    dir + " is missing — the config classes moved; update CONFIG_CLASS_DIRS");
            forEachJavaSource(classes, text -> {
                Matcher m = CONFIG_VALUE_DECL.matcher(text);
                while (m.find()) {
                    defined.add(m.group(1));
                }
            });
        }

        // Android build flags: quoted key literals in the gate-policy package.
        Path android = root.resolve(ANDROID_FLAG_DIR);
        assertTrue(Files.isDirectory(android),
                ANDROID_FLAG_DIR + " is missing — the build-flag declarations moved; "
                        + "update ANDROID_FLAG_DIR");
        forEachJavaSource(android, text -> {
            Matcher m = ANDROID_FLAG_DECL.matcher(text);
            while (m.find()) {
                defined.add(m.group(1));
            }
        });

        assertTrue(defined.size() >= 10,
                "only " + defined.size() + " config keys discovered from " + CONFIG_YML
                        + " and the config classes — extraction is broken and this rule "
                        + "would pass vacuously");
        return defined;
    }

    private static Object field(Object node, String name) {
        return node instanceof Map<?, ?> map ? map.get(name) : null;
    }

    /** Adds every node path under {@code path} — leaves and intermediates alike. */
    private static void collectConfigPaths(Object node, String path, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String child = path + "." + key;
                out.add(child);
                collectConfigPaths(map.get(key), child, out);
            }
        }
    }

    private static void forEachJavaSource(Path dir, Consumer<String> visitor) throws IOException {
        List<Path> sources;
        try (var stream = Files.walk(dir)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        for (Path source : sources) {
            visitor.accept(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
        }
    }

    private static List<String> findConfigKeyViolations(String label, String text, Set<String> defined) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = CONFIG_KEY.matcher(lines[i]);
            while (m.find()) {
                String key = m.group();
                if (!configKeyDefined(key, defined)) {
                    String where = (label == null ? "line " + (i + 1) : label + ":" + (i + 1));
                    found.add(where + ": cites " + key + " — not defined in " + CONFIG_YML
                            + " or the config classes (defined: " + String.join(", ", defined) + ")");
                }
            }
        }
        return found;
    }

    /** A key counts when declared outright, or when it names a namespace of declared keys. */
    private static boolean configKeyDefined(String key, Set<String> defined) {
        if (defined.contains(key)) {
            return true;
        }
        String prefix = key + ".";
        return defined.stream().anyMatch(k -> k.startsWith(prefix));
    }
}
