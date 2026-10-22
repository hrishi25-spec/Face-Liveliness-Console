package io.mosip.liveness;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed guard against compiling against an artifact that only happens to
 * be on the classpath.
 *
 * <p>The Jackson 2 coupling was possible because {@code jackson-databind} 2.x
 * arrived transitively through springdoc, so the service compiled against a
 * library it never declared and did not run on. Nothing about the build said
 * so; the failure appeared at runtime, after a Boot upgrade changed the mapper
 * underneath it.</p>
 *
 * <p>This test makes that class of mistake loud and early. It resolves every
 * third-party import under {@code src/main/java} and {@code src/test/java} to
 * the jar that provides the class, reads that jar's Maven coordinates, and
 * requires each artifact to be either</p>
 *
 * <ul>
 *   <li>declared in {@code pom.xml}, or</li>
 *   <li>listed in {@link #PROVIDED_BY} against the <em>declared</em> dependency
 *       that legitimately brings it — starters and the handful of libraries
 *       whose transitives are part of their contract.</li>
 * </ul>
 *
 * <p>Both halves are checked: an undeclared import fails, and an entry whose
 * provider is no longer declared fails too, so the ledger cannot rot after a
 * dependency is replaced. A {@code src/main} import of a test-scope artifact
 * fails separately — that is how a class ends up compiled but missing from the
 * runnable jar.</p>
 *
 * <p>Three more couplings share that failure mode — compiles, tests pass, jar
 * does not work — and are checked here rather than left to a packaging script
 * that only runs after a release:</p>
 *
 * <ul>
 *   <li>a {@code provided} import outside the host-integration package, which
 *       the packaged jar cannot satisfy because the dependency is excluded from
 *       it ({@link #providedScopeImportsStayInTheHostIntegrationPackage()});</li>
 *   <li>a {@code provided} or {@code optional} dependency that is not actually
 *       excluded — it ships and can shadow the host's copy — or an exclusion of
 *       something the service does need at runtime
 *       ({@link #providedAndOptionalArtifactsAreTheOnesLeftOutOfTheJar()});</li>
 *   <li>a split library on two versions because one member was pinned and the
 *       rest were left to dependency management — {@code junit-jupiter} 6.1.3
 *       next to its 6.0.3 modules was exactly this
 *       ({@link #versionFamiliesStayInStep()}).</li>
 * </ul>
 *
 * <p>Adding a dependency is meant to be a decision. When this test fails,
 * either declare the artifact (adding it to the pom, where the build can manage
 * its version) or add one line to {@link #PROVIDED_BY} naming the dependency
 * that brings it — with a reason, because the next reader will ask.</p>
 */
class DependencyOwnershipGateTest {

    private static final List<String> SOURCE_ROOTS = List.of("src/main/java", "src/test/java");
    private static final String MAIN_ROOT = "src/main/java";
    private static final String OWN_PACKAGE = "io.mosip.liveness.";
    private static final String SPRING_BOOT_PLUGIN = "spring-boot-maven-plugin";

    /**
     * Packages that ship with the JDK. {@code jrt:} URLs are skipped at
     * resolution time as well; this list only keeps the failure message sane for
     * packages the loader answers for without a jar.
     */
    private static final List<String> JDK_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "org.ietf.");

    /**
     * Third-party artifacts the service compiles against without declaring them,
     * each mapped to the declared dependency that is expected to bring it.
     *
     * <p>Every value must name a dependency declared in {@code pom.xml}; the
     * provider is what makes the coupling a contract rather than an accident.
     * Spring's starters are the bulk of this table: importing {@code spring-web}
     * is correct because {@code spring-boot-starter-web} is the decision, and the
     * starter owns that version. Jackson's annotations are shared with Jackson 3's
     * databind, which is declared; that is explained in {@code docs/jackson-3.md}.</p>
     */
    private static final Map<String, String> PROVIDED_BY = new LinkedHashMap<>();

    /**
     * Declared dependencies whose jar is deliberately <em>not</em> in the
     * packaged artifact, mapped to the source package allowed to import them.
     *
     * <p>These are the host-integrated libraries: the MOSIP Registration Client
     * is a JavaFX application and supplies JavaFX at runtime, so the overlay
     * compiles against it and the service jar leaves it out. That works only
     * while the import sits in the overlay package. A JavaFX import anywhere
     * else compiles, passes every test (surefire puts provided dependencies on
     * the test classpath), and then dies with {@code NoClassDefFoundError} the
     * first time the packaged jar touches that class. Naming the package here is
     * what keeps the compile-time freedom from leaking into the service.</p>
     */
    private static final Map<String, String> HOST_PROVIDED = new LinkedHashMap<>();

    static {
        // Spring Boot starters: the declared starter is the dependency; the
        // modules below it are its public surface, not separate decisions.
        String starterWeb = "org.springframework.boot:spring-boot-starter-web";
        String starterTest = "org.springframework.boot:spring-boot-starter-test";
        String starterJpa = "org.springframework.boot:spring-boot-starter-data-jpa";
        String starterValidation = "org.springframework.boot:spring-boot-starter-validation";
        String starterFlyway = "org.springframework.boot:spring-boot-starter-flyway";
        String jpaTestSlice = "org.springframework.boot:spring-boot-data-jpa-test";
        String junitAggregator = "org.junit.jupiter:junit-jupiter";
        String javafxControls = "org.openjfx:javafx-controls";
        String jacksonDatabind = "tools.jackson.core:jackson-databind";

        for (String module : List.of("spring-core", "spring-beans", "spring-context",
                "spring-web", "spring-webmvc")) {
            PROVIDED_BY.put("org.springframework:" + module, starterWeb);
        }
        for (String module : List.of("spring-boot", "spring-boot-autoconfigure")) {
            PROVIDED_BY.put("org.springframework.boot:" + module, starterWeb);
        }
        PROVIDED_BY.put("org.springframework:spring-test", starterTest);
        PROVIDED_BY.put("org.springframework.boot:spring-boot-test", starterTest);
        PROVIDED_BY.put("org.springframework.boot:spring-boot-jpa-test", jpaTestSlice);
        PROVIDED_BY.put("org.springframework.data:spring-data-commons", starterJpa);
        PROVIDED_BY.put("org.springframework.data:spring-data-jpa", starterJpa);
        PROVIDED_BY.put("org.springframework:spring-jdbc", starterJpa);
        PROVIDED_BY.put("org.springframework:spring-tx", starterJpa);
        PROVIDED_BY.put("jakarta.persistence:jakarta.persistence-api", starterJpa);
        PROVIDED_BY.put("jakarta.validation:jakarta.validation-api", starterValidation);
        PROVIDED_BY.put("jakarta.annotation:jakarta.annotation-api", starterWeb);
        // Servlet API: Spring MVC's handler signatures use it, Tomcat provides it.
        PROVIDED_BY.put("org.apache.tomcat.embed:tomcat-embed-core", starterWeb);
        PROVIDED_BY.put("org.yaml:snakeyaml", starterWeb);
        PROVIDED_BY.put("org.slf4j:slf4j-api", starterWeb);
        PROVIDED_BY.put("ch.qos.logback:logback-classic", starterWeb);
        PROVIDED_BY.put("ch.qos.logback:logback-core", starterWeb);
        PROVIDED_BY.put("org.flywaydb:flyway-core", starterFlyway);

        // Test stack: the aggregator is declared, its parts are not.
        for (String module : List.of("junit-jupiter-api", "junit-jupiter-params")) {
            PROVIDED_BY.put("org.junit.jupiter:" + module, junitAggregator);
        }
        PROVIDED_BY.put("org.mockito:mockito-core", starterTest);
        PROVIDED_BY.put("org.assertj:assertj-core", starterTest);

        // JavaFX is layered: controls depends on graphics, graphics on base. The
        // pom declares the modules it uses; the parents arrive with them.
        PROVIDED_BY.put("org.openjfx:javafx-graphics", javafxControls);
        PROVIDED_BY.put("org.openjfx:javafx-base", javafxControls);

        // Jackson 3 splits core from databind, and databind pulls the 2.x
        // annotations package it reads. Declaring databind is the decision.
        PROVIDED_BY.put("tools.jackson.core:jackson-core", jacksonDatabind);
        PROVIDED_BY.put("com.fasterxml.jackson.core:jackson-annotations", jacksonDatabind);

        // Host-supplied libraries. The overlay package is the only place allowed
        // to import them, because the packaged jar excludes them by design.
        HOST_PROVIDED.put("org.openjfx:javafx-controls", "io.mosip.liveness.client");
        HOST_PROVIDED.put("org.openjfx:javafx-fxml", "io.mosip.liveness.client");
    }

    /**
     * Artifacts that share a release train but are versioned independently.
     *
     * <p>Every other group on the classpath must be internally consistent: a
     * library split into modules is compiled together and calls across the split
     * at exact method signatures, so a half-upgraded family fails on whichever
     * path happens to reach the changed method — usually not the one the tests
     * cover. These entries are libraries that merely share a groupId, or ship on
     * their own cadence, so comparing them would invent a rule the upstreams do
     * not have.</p>
     */
    private static final Set<String> INDEPENDENT_VERSIONING = new TreeSet<>(List.of(
            // bcrypt's byte-array helper; released on its own line.
            "at.favre.lib:bytes",
            // Jackson 2's annotations ship as 2.21 while core/databind ship 2.21.5.
            "com.fasterxml.jackson.core:jackson-annotations",
            // Separate Apache Commons libraries that happen to share a group.
            "org.apache.commons:commons-compress",
            "org.apache.commons:commons-lang3",
            // Monocle tracks javafx.version; testfx-core has its own lifecycle.
            "org.testfx:openjfx-monocle",
            "org.testfx:testfx-core",
            // Independently versioned webjars.
            "org.webjars:swagger-ui",
            "org.webjars:webjars-locator-lite"));

    @Test
    void everyThirdPartyImportIsDeclaredOrOwnedByDeclaredDependency() throws IOException {
        Declared declared = Declared.fromPom();
        Map<String, List<String>> violations = new TreeMap<>();
        Set<String> seenArtifacts = new HashSet<>();

        for (SourceImport sourceImport : imports()) {
            Resolution resolution = resolve(sourceImport.imported());
            if (resolution.jdk()) {
                continue;
            }
            if (resolution.artifacts().isEmpty()) {
                // Fail closed: an import this gate cannot attribute could be the
                // next unseen transitive. Better a red test than a blind spot.
                violations.computeIfAbsent(sourceImport.imported(), key -> new ArrayList<>())
                        .add(sourceImport.location() + " does not resolve to a jar in the Maven repository");
                continue;
            }
            seenArtifacts.addAll(resolution.artifacts());
            String artifact = resolution.artifacts().stream()
                    .filter(candidate -> declared.has(candidate) || PROVIDED_BY.containsKey(candidate))
                    .findFirst()
                    .orElseGet(() -> resolution.artifacts().iterator().next());

            String owner = PROVIDED_BY.get(artifact);
            if (!declared.has(artifact) && owner == null) {
                violations.computeIfAbsent(artifact, key -> new ArrayList<>())
                        .add(sourceImport.location() + " imports " + sourceImport.imported()
                                + " from an artifact that is neither declared nor owned");
                continue;
            }
            if (!declared.has(artifact) && !declared.has(owner)) {
                violations.computeIfAbsent(artifact, key -> new ArrayList<>())
                        .add(sourceImport.location() + " relies on " + artifact + ", whose owner "
                                + owner + " is not declared any more");
                continue;
            }

            // Production code must not be compiled against an artifact that only
            // the test scope provides: it compiles here and is missing from the
            // runnable jar. The scope of the provider counts too - spring-test is
            // excused through the test starter, not declared by itself.
            String scope = declared.has(artifact) ? declared.scope(artifact) : declared.scope(owner);
            if ("test".equals(scope) && sourceImport.file().startsWith(MAIN_ROOT)) {
                violations.computeIfAbsent(artifact, key -> new ArrayList<>())
                        .add(sourceImport.location() + " imports test-scope " + artifact
                                + (declared.has(artifact) ? "" : " (through " + owner + ")"));
            }
        }

        // An entry nobody needs is a stale excuse, and stale entries are how a
        // fail-closed list quietly turns into a rubber stamp.
        for (Map.Entry<String, String> excuse : PROVIDED_BY.entrySet()) {
            if (!seenArtifacts.contains(excuse.getKey())) {
                violations.computeIfAbsent(excuse.getKey(), key -> new ArrayList<>())
                        .add("stale ownership entry: nothing imports " + excuse.getKey()
                                + " any more (owner " + excuse.getValue() + ")");
            }
        }

        assertTrue(violations.isEmpty(), () -> {
            StringBuilder message = new StringBuilder("""
                    A third-party artifact reaches the compiler without a declared owner. \
                    Declare it in pom.xml, or add one line to PROVIDED_BY naming the declared \
                    dependency that brings it - and say why in the code comment.\
                    """);
            violations.forEach((artifact, lines) -> {
                message.append("\n\n  ").append(artifact);
                for (String line : lines) {
                    message.append("\n    ").append(line);
                }
            });
            return message.toString();
        });
    }

    /**
     * A library split across several artifacts must be one version on the
     * classpath.
     *
     * <p>Every module of a split library is built against its siblings at the
     * exact patch it was released with, so a half-upgraded family links fine and
     * then fails with {@code NoSuchMethodError} or {@code AbstractMethodError} on
     * whichever path reaches the changed method. This build carried the shape: the
     * pom pinned the JUnit aggregator to 6.1.3, while spring-boot-starter-parent's
     * dependency management kept {@code junit-jupiter-api}, {@code -params} and
     * {@code -engine} on 6.0.3 — every test passed and the engine was a version the
     * pom never mentioned. The fix is to let the parent own the family, not to pin
     * one member of it.</p>
     *
     * <p>Groups that are not one release train are named in
     * {@link #INDEPENDENT_VERSIONING}; the stale-entry half keeps that list from
     * outliving the dependency it excuses.</p>
     */
    @Test
    void versionFamiliesStayInStep() {
        Map<String, String> versions = ClasspathIndex.versionsByArtifact();
        Map<String, Map<String, String>> families = new TreeMap<>();
        for (Map.Entry<String, String> artifact : versions.entrySet()) {
            if (INDEPENDENT_VERSIONING.contains(artifact.getKey())) {
                continue;
            }
            int separator = artifact.getKey().lastIndexOf(':');
            families.computeIfAbsent(artifact.getKey().substring(0, separator), key -> new TreeMap<>())
                    .put(artifact.getKey(), artifact.getValue());
        }

        Map<String, List<String>> violations = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> family : families.entrySet()) {
            Set<String> distinct = new TreeSet<>(family.getValue().values());
            if (distinct.size() > 1) {
                violations.computeIfAbsent(family.getKey(), key -> new ArrayList<>())
                        .add("is one release train at " + String.join("/", distinct)
                                + ": " + family.getValue());
            }
        }
        for (String exempt : INDEPENDENT_VERSIONING) {
            if (!versions.containsKey(exempt)) {
                violations.computeIfAbsent(exempt, key -> new ArrayList<>())
                        .add("is exempt from version lockstep but is not on the classpath any more");
            }
        }

        assertTrue(violations.isEmpty(), () -> {
            StringBuilder message = new StringBuilder("""
                    One library is on the classpath at more than one version. Let the parent or a \
                    BOM own the whole family, or upgrade every member together; do not pin one \
                    artifact of a split library.\
                    """);
            violations.forEach((group, lines) -> {
                message.append("\n\n  ").append(group);
                for (String line : lines) {
                    message.append("\n    ").append(line);
                }
            });
            return message.toString();
        });
    }

    /**
     * The runnable jar's exclusions must be exactly the dependencies the service
     * does not ship.
     *
     * <p>Two silent outcomes are possible here. A {@code provided} or
     * {@code optional} dependency that is not excluded lands in
     * {@code BOOT-INF/lib}, where it can shadow the copy the host supplies — the
     * JavaFX modules did exactly that until the plugin learned to drop them. In
     * the other direction, excluding a dependency the service calls at runtime
     * produces a jar that compiles, tests and packages cleanly and then fails on
     * the first request that reaches the missing class.</p>
     *
     * <p>The {@code slim} profile restates the base exclusions because Maven
     * merges profile plugin configuration positionally; a base exclusion it does
     * not repeat comes back in the slim jar. That is asserted too, so the two
     * lists cannot drift apart.</p>
     */
    @Test
    void providedAndOptionalArtifactsAreTheOnesLeftOutOfTheJar() throws IOException {
        Declared declared = Declared.fromPom();
        Set<String> notShipped = new TreeSet<>();
        for (Map.Entry<String, Declared.Spec> dependency : declared.specs().entrySet()) {
            Declared.Spec spec = dependency.getValue();
            if ("provided".equals(spec.scope()) || spec.optional()) {
                notShipped.add(dependency.getKey());
            }
        }

        Set<String> baseExcludes = new TreeSet<>(declared.excludesFromJar(null));
        Set<String> slimExcludes = new TreeSet<>(declared.excludesFromJar("slim"));
        Map<String, List<String>> violations = new TreeMap<>();

        for (String dependency : notShipped) {
            if (!baseExcludes.contains(dependency)) {
                violations.computeIfAbsent(dependency, key -> new ArrayList<>())
                        .add("is provided or optional but not excluded from the jar; it would ship"
                                + " and could shadow the host's copy");
            }
        }
        for (String excluded : baseExcludes) {
            // A parent module (javafx-graphics) is not declared itself; its
            // declared parent is what makes its exclusion a decision.
            String declaring = notShipped.contains(excluded) ? excluded : PROVIDED_BY.get(excluded);
            if (declaring == null || !notShipped.contains(declaring)) {
                violations.computeIfAbsent(excluded, key -> new ArrayList<>())
                        .add("is excluded from the jar but is not provided or optional"
                                + (declaring == null ? " and no declared dependency owns it"
                                        : " (owner " + declaring + " is not host-supplied)")
                                + "; the service would call a class that is not in the artifact");
            }
        }
        for (String base : baseExcludes) {
            if (!slimExcludes.contains(base)) {
                violations.computeIfAbsent(base, key -> new ArrayList<>())
                        .add("is excluded in the base plugin configuration but not restated in the"
                                + " slim profile, so the slim jar ships it again");
            }
        }
        for (Map.Entry<String, String> hostProvided : HOST_PROVIDED.entrySet()) {
            String dependency = hostProvided.getKey();
            if (!declared.has(dependency)) {
                violations.computeIfAbsent(dependency, key -> new ArrayList<>())
                        .add("stale host-provided entry: the dependency is no longer declared");
            } else if (!"provided".equals(declared.scope(dependency))) {
                violations.computeIfAbsent(dependency, key -> new ArrayList<>())
                        .add("is in HOST_PROVIDED but declared with scope "
                                + declared.scope(dependency) + ", so the jar ships it");
            }
        }

        assertTrue(violations.isEmpty(), () -> {
            StringBuilder message = new StringBuilder("""
                    The declarations and the packaged jar disagree about what the service ships. \
                    Keep <scope>provided</scope>/<optional>true</optional> and the \
                    spring-boot-maven-plugin <excludes> in step.\
                    """);
            violations.forEach((dependency, lines) -> {
                message.append("\n\n  ").append(dependency);
                for (String line : lines) {
                    message.append("\n    ").append(line);
                }
            });
            return message.toString();
        });
    }

    /**
     * A {@code provided} dependency may only be imported from the package that
     * integrates with the host that supplies it.
     *
     * <p>Provided dependencies are on the compile and test classpath but not in
     * the jar, so an import outside {@link #HOST_PROVIDED}'s allowed package is
     * the purest form of compile-only-by-accident: javac is happy, the suite is
     * green, and the packaged service throws {@code NoClassDefFoundError} at the
     * point of use. Tests are exempt — surefire resolves the dependency — and so
     * is every non-provided scope, which this test leaves to the ownership
     * check above.</p>
     */
    @Test
    void providedScopeImportsStayInTheHostIntegrationPackage() throws IOException {
        Declared declared = Declared.fromPom();
        Map<String, List<String>> violations = new TreeMap<>();

        for (SourceImport sourceImport : imports()) {
            if (!sourceImport.file().startsWith(MAIN_ROOT)) {
                continue;
            }
            Resolution resolution = resolve(sourceImport.imported());
            if (resolution.jdk() || resolution.artifacts().isEmpty()) {
                continue; // the ownership check reports what cannot be resolved
            }
            for (String artifact : resolution.artifacts()) {
                String dependency = declared.has(artifact) ? artifact : PROVIDED_BY.get(artifact);
                if (dependency == null || !"provided".equals(declared.scope(dependency))) {
                    continue;
                }
                String allowedPackage = HOST_PROVIDED.get(dependency);
                if (allowedPackage == null) {
                    violations.computeIfAbsent(dependency, key -> new ArrayList<>())
                            .add("is provided but has no HOST_PROVIDED entry, so no import site is"
                                    + " allowed: " + sourceImport.location());
                    continue;
                }
                if (!sourceImport.file().startsWith(packageDirectory(allowedPackage))) {
                    violations.computeIfAbsent(dependency, key -> new ArrayList<>())
                            .add(sourceImport.location() + " imports " + sourceImport.imported()
                                    + ", which is not in the jar; only " + allowedPackage
                                    + " may use this host-supplied dependency");
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> {
            StringBuilder message = new StringBuilder("""
                    A class in the service imports a dependency the packaged jar does not contain. \
                    Move the host integration into the allowed package, or make the dependency \
                    part of the service (compile scope, no exclusion).\
                    """);
            violations.forEach((dependency, lines) -> {
                message.append("\n\n  ").append(dependency);
                for (String line : lines) {
                    message.append("\n    ").append(line);
                }
            });
            return message.toString();
        });
    }

    private static String packageDirectory(String javaPackage) {
        return MAIN_ROOT + "/" + javaPackage.replace('.', '/') + "/";
    }

    // ------------------------------------------------------------------ imports

    private record SourceImport(Path file, int line, String imported) {
        String location() {
            return file + ":" + line;
        }
    }

    private static List<SourceImport> imports() throws IOException {
        List<SourceImport> found = new ArrayList<>();
        for (String root : SOURCE_ROOTS) {
            Path path = Path.of(root);
            assertTrue(Files.isDirectory(path), "source root must exist: " + path.toAbsolutePath());
            try (Stream<Path> files = Files.walk(path)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (!line.startsWith("import ")) {
                            continue;
                        }
                        String imported = line.substring("import ".length())
                                .replace("static ", "")
                                .replace(";", "")
                                .trim();
                        if (imported.startsWith(OWN_PACKAGE) || imported.equals("io.mosip.liveness")) {
                            continue;
                        }
                        if (JDK_PREFIXES.stream().anyMatch(imported::startsWith)) {
                            continue;
                        }
                        found.add(new SourceImport(file, i + 1, imported));
                    }
                }
            }
        }
        return found;
    }

    // --------------------------------------------------------------- resolution

    /**
     * The {@code group:artifact} coordinates that provide an import, or
     * {@code jdk} for the JDK. A wildcard import names a package, so it can have
     * more than one answer when a package is split across jars.
     */
    private record Resolution(Set<String> artifacts, boolean jdk) {

        static Resolution of(Set<String> artifacts) {
            return new Resolution(Collections.unmodifiableSet(new TreeSet<>(artifacts)), false);
        }

        static Resolution ofJdk() {
            return new Resolution(Set.of(), true);
        }

        static Resolution none() {
            return new Resolution(Set.of(), false);
        }
    }

    /**
     * Resolves an import to the jar holding it.
     *
     * <p>A class import is looked up through the class loader, so the answer is
     * the jar the compiler itself would have read. A static import names a
     * member, not a class ({@code lombok.Data.builder}), so segments are dropped
     * from the right until a class file is found. A wildcard import names a
     * package, which has no class file to ask for, so it is resolved against the
     * jars on {@code java.class.path}. The jar's Maven coordinates are read from
     * its {@code pom.properties} or from its path inside the local repository —
     * no dependency graph is consulted, so the answer reflects what the compiler
     * actually saw.</p>
     */
    private static Resolution resolve(String imported) {
        if (imported.endsWith(".*")) {
            String prefix = imported.substring(0, imported.length() - 2);
            // A static wildcard names a class (`Mockito.*` is every static member
            // of Mockito), a plain wildcard names a package. Try the class first:
            // it is the only reading that satisfies both.
            Resolution asClass = resolveClass(prefix);
            if (asClass.jdk() || !asClass.artifacts().isEmpty()) {
                return asClass;
            }
            return Resolution.of(ClasspathIndex.artifactsInPackage(prefix));
        }
        return resolveClass(imported);
    }

    private static Resolution resolveClass(String imported) {
        String[] parts = imported.split("\\.");
        for (int length = parts.length; length > 1; length--) {
            String resource = String.join("/", List.of(parts).subList(0, length)) + ".class";
            for (URL url : resources(resource)) {
                String protocol = url.getProtocol();
                if ("jrt".equals(protocol)) {
                    return Resolution.ofJdk(); // a JDK module
                }
                if (!"jar".equals(protocol)) {
                    continue;
                }
                Optional<String> artifact = coordinates(jarPath(url));
                if (artifact.isPresent()) {
                    return Resolution.of(Set.of(artifact.get()));
                }
            }
        }
        return Resolution.none();
    }

    private static List<URL> resources(String resource) {
        try {
            return List.copyOf(Collections.list(
                    DependencyOwnershipGateTest.class.getClassLoader().getResources(resource)));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String jarPath(URL url) {
        String path = url.getPath();
        int separator = path.indexOf("!/");
        if (separator >= 0) {
            path = path.substring(0, separator);
        }
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        return path;
    }

    /**
     * {@code .../.m2/repository/<group dirs>/<artifact>/<version>/<file>.jar}
     * to {@code group:artifact}. Anything outside a Maven repository layout is
     * not a dependency this gate can judge, so it is ignored.
     */
    private static Optional<String> coordinates(String jarPath) {
        String normalised = jarPath.replace('\\', '/');
        int repository = normalised.lastIndexOf("/repository/");
        if (repository < 0) {
            return Optional.empty();
        }
        String[] segments = normalised.substring(repository + "/repository/".length()).split("/");
        if (segments.length < 3) {
            return Optional.empty();
        }
        String artifact = segments[segments.length - 3];
        String group = String.join(".", List.of(segments).subList(0, segments.length - 3));
        return group.isEmpty() ? Optional.empty() : Optional.of(group + ":" + artifact);
    }

    /**
     * Index of the compiler's classpath, read once from {@code java.class.path}.
     *
     * <p>Needed for the questions the class loader cannot answer: which jar owns a
     * <em>package</em> (wildcard imports), and which version of an artifact the
     * compiler was handed. Both come from the jars the forked surefire JVM was
     * given, so they describe this build rather than a re-resolved one.</p>
     */
    private static final class ClasspathIndex {

        private static Map<String, Set<String>> artifactsByPackage;
        private static Map<String, String> versionsByArtifact;

        static synchronized Set<String> artifactsInPackage(String javaPackage) {
            build();
            return artifactsByPackage.getOrDefault(javaPackage.replace('.', '/'), Set.of());
        }

        static synchronized Map<String, String> versionsByArtifact() {
            build();
            return versionsByArtifact;
        }

        private static void build() {
            if (artifactsByPackage != null) {
                return;
            }
            Map<String, Set<String>> packages = new HashMap<>();
            Map<String, String> versions = new TreeMap<>();
            String classpath = System.getProperty("java.class.path", "");
            assertTrue(!classpath.isBlank(),
                    "java.class.path is empty: this gate cannot see what the compiler was given");

            int jars = 0;
            for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
                if (!entry.endsWith(".jar")) {
                    continue;
                }
                Path jar = Path.of(entry);
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                jars++;
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    Optional<String> fromMetadata = declaredCoordinates(zip);
                    Optional<String> artifact = fromMetadata.isPresent()
                            ? fromMetadata
                            : coordinates(entry);
                    if (artifact.isEmpty()) {
                        continue; // not a Maven artifact; it owns nothing this gate judges
                    }
                    versions.putIfAbsent(artifact.get(), version(entry).orElse(""));
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                            continue;
                        }
                        int slash = name.lastIndexOf('/');
                        if (slash <= 0) {
                            continue; // a class in the default package
                        }
                        packages.computeIfAbsent(name.substring(0, slash), key -> new HashSet<>())
                                .add(artifact.get());
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "cannot read " + jar + " from the compiler classpath", e);
                }
            }
            assertTrue(jars > 0, "no jars on java.class.path: this gate refuses to pass blind");
            artifactsByPackage = packages;
            versionsByArtifact = Collections.unmodifiableMap(versions);
        }

        /** The coordinates a jar declares about itself, when it carries its pom. */
        private static Optional<String> declaredCoordinates(ZipFile zip) throws IOException {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties")) {
                    continue;
                }
                Properties properties = new Properties();
                try (InputStream in = zip.getInputStream(entry)) {
                    properties.load(in);
                }
                String group = properties.getProperty("groupId");
                String artifact = properties.getProperty("artifactId");
                if (group != null && !group.isBlank() && artifact != null && !artifact.isBlank()) {
                    return Optional.of(group.trim() + ":" + artifact.trim());
                }
            }
            return Optional.empty();
        }

        private static Optional<String> version(String jarPath) {
            String normalised = jarPath.replace('\\', '/');
            int repository = normalised.lastIndexOf("/repository/");
            if (repository < 0) {
                return Optional.empty();
            }
            String[] segments = normalised.substring(repository + "/repository/".length()).split("/");
            return segments.length >= 3 ? Optional.of(segments[segments.length - 2]) : Optional.empty();
        }
    }

    // -------------------------------------------------------------------- pom

    /** The project's declared dependencies and their scopes. */
    private record Declared(Map<String, Spec> specs) {

        /** A declared dependency. */
        record Spec(String scope, boolean optional) {
        }

        static Declared fromPom() throws IOException {
            Path pom = Path.of("pom.xml");
            assertTrue(Files.isRegularFile(pom),
                    "pom.xml must be readable from the test working directory: " + Path.of("").toAbsolutePath());
            Document document;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                document = factory.newDocumentBuilder().parse(pom.toFile());
            } catch (Exception e) {
                throw new IOException("cannot parse " + pom.toAbsolutePath(), e);
            }
            Element dependencies = firstChild(document.getDocumentElement(), "dependencies");
            if (dependencies == null) {
                throw new IOException("pom.xml declares no <dependencies>");
            }
            Map<String, Spec> specs = new HashMap<>();
            NodeList children = dependencies.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element dependency) || !"dependency".equals(dependency.getTagName())) {
                    continue;
                }
                String group = text(dependency, "groupId");
                String artifact = text(dependency, "artifactId");
                String scope = text(dependency, "scope");
                if (artifact.isEmpty()) {
                    continue;
                }
                specs.put(group + ":" + artifact, new Spec(
                        scope.isEmpty() ? "compile" : scope,
                        "true".equals(text(dependency, "optional"))));
            }
            return new Declared(specs);
        }

        boolean has(String artifact) {
            return specs.containsKey(artifact);
        }

        String scope(String artifact) {
            Spec spec = specs.get(artifact);
            return spec == null ? "compile" : spec.scope();
        }

        /** The artifacts the given spring-boot plugin configuration leaves out of the jar. */
        Set<String> excludesFromJar(String profileId) throws IOException {
            Path pom = Path.of("pom.xml");
            Document document;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                document = factory.newDocumentBuilder().parse(pom.toFile());
            } catch (Exception e) {
                throw new IOException("cannot parse " + pom.toAbsolutePath(), e);
            }
            Element project = document.getDocumentElement();
            Element scope = project;
            if (profileId != null) {
                scope = null;
                Element profiles = firstChild(project, "profiles");
                NodeList candidates = profiles == null ? null : profiles.getChildNodes();
                for (int i = 0; candidates != null && i < candidates.getLength(); i++) {
                    if (candidates.item(i) instanceof Element profile
                            && profileId.equals(text(profile, "id"))) {
                        scope = profile;
                        break;
                    }
                }
                assertTrue(scope != null, "pom.xml has no profile '" + profileId + "' to check");
            }
            Element build = firstChild(scope, "build");
            Element plugins = build == null ? null : firstChild(build, "plugins");
            Set<String> excludes = new TreeSet<>();
            NodeList pluginNodes = plugins == null ? null : plugins.getChildNodes();
            for (int i = 0; pluginNodes != null && i < pluginNodes.getLength(); i++) {
                if (!(pluginNodes.item(i) instanceof Element plugin)
                        || !"plugin".equals(plugin.getTagName())
                        || !SPRING_BOOT_PLUGIN.equals(text(plugin, "artifactId"))) {
                    continue;
                }
                Element configuration = firstChild(plugin, "configuration");
                Element excludeList = configuration == null ? null : firstChild(configuration, "excludes");
                NodeList excludeNodes = excludeList == null ? null : excludeList.getChildNodes();
                for (int j = 0; excludeNodes != null && j < excludeNodes.getLength(); j++) {
                    if (excludeNodes.item(j) instanceof Element exclude
                            && "exclude".equals(exclude.getTagName())) {
                        String group = text(exclude, "groupId");
                        String artifact = text(exclude, "artifactId");
                        if (!artifact.isEmpty()) {
                            excludes.add(group + ":" + artifact);
                        }
                    }
                }
            }
            assertTrue(!excludes.isEmpty(),
                    SPRING_BOOT_PLUGIN + " declares no excludes in "
                            + (profileId == null ? "pom.xml" : "profile " + profileId));
            return excludes;
        }

        private static Element firstChild(Element parent, String name) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                    return element;
                }
            }
            return null;
        }

        private static String text(Element parent, String name) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                    return element.getTextContent().trim();
                }
            }
            return "";
        }
    }
}
