package io.mosip.liveness;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-closed guard against the service drifting back onto Jackson 2.
 *
 * <p>Spring Boot 4 serves JSON with Jackson 3 ({@code tools.jackson}), but
 * springdoc 3.x renders the OpenAPI document through swagger-core 2.2.x, which
 * is Jackson 2 based. That keeps {@code com.fasterxml.jackson.core:jackson-databind}
 * on the runtime classpath, so Jackson 2 imports still <em>compile</em> even
 * though they are not the mapper the service actually runs with. That is
 * exactly how the production converters and {@code LivenessHttpClient} came to
 * be pinned to a springdoc transitive: nothing failed, and the coupling only
 * surfaced when Boot 4 changed the mapper underneath them.</p>
 *
 * <p>The one legitimate {@code com.fasterxml.jackson} package is
 * {@code com.fasterxml.jackson.annotation}: Jackson 3's {@code jackson-databind}
 * depends on {@code jackson-annotations} 2.x, so the DTOs' {@code @JsonInclude}
 * imports are correct and shared by both mappers. Everything else must be
 * {@code tools.jackson}. Swagger-core may keep its own Jackson 2 mapper; this
 * service's code may not use it.</p>
 *
 * <p>Imports alone are not the whole surface. A Jackson 2 coordinate declared in
 * {@code pom.xml} — {@code jackson-databind}, a {@code dataformat-*} module, a
 * test-scoped {@code jackson-module-*} — would be a deliberate Jackson 2 pin on
 * the same classpath the service runs from, and it would arrive with a version
 * this project chose rather than one springdoc transitively dragged in. So the
 * gate reads the pom too, every {@code <dependency>} in it including the ones
 * inside profiles and {@code <dependencyManagement>}.</p>
 *
 * <p>A BOM import ({@code <type>pom</type>}) is the one exception: it pins
 * versions for dependencies declared elsewhere and ships no jar of its own, so
 * it cannot put Jackson 2 on the classpath by itself. Importing the Jackson 2
 * BOM would still show up the moment an artifact from it is declared.</p>
 */
class JacksonRuntimeGateTest {

    /** Compiled by the service; a Jackson 2 import here is a real coupling. */
    private static final List<String> SOURCE_ROOTS = List.of("src/main/java", "src/test/java");

    /** Jackson 2's package root; only the `.annotation.` subpackage is shared. */
    private static final String JACKSON_2 = "com.fasterxml.jackson.";
    private static final String SHARED_ANNOTATIONS = "com.fasterxml.jackson.annotation.";

    /** Jackson 2's Maven group prefix, e.g. `com.fasterxml.jackson.core`. */
    private static final String JACKSON_2_GROUP = "com.fasterxml.jackson";

    @Test
    void noSourceFileImportsJackson2CoreOrDatabind() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SOURCE_ROOTS) {
            Path path = Path.of(root);
            assertTrue(Files.isDirectory(path), "source root must exist: " + path.toAbsolutePath());
            try (Stream<Path> files = Files.walk(path)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .sorted()
                        .forEach(file -> collect(file, violations));
            }
        }

        assertTrue(violations.isEmpty(), () -> """
                Jackson 2 is only on the classpath because springdoc's swagger-core needs it; \
                the service itself must use Jackson 3 (tools.jackson). \
                Update these imports, keeping com.fasterxml.jackson.annotation (shared by both):
                  """ + String.join("\n  ", violations));
    }

    /**
     * No Jackson 2 coordinate may be declared in {@code pom.xml}.
     *
     * <p>This is the other half of the import rule: an import can be fixed by
     * editing one line, while a declared dependency changes what the whole
     * module — and every artifact repackaged from it — resolves against.</p>
     */
    @Test
    void noPomDependencyDeclaresJackson2() throws IOException {
        List<String> violations = new ArrayList<>();
        NodeList dependencies = readPom().getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            if (!(dependencies.item(i) instanceof Element dependency)) {
                continue;
            }
            String groupId = text(dependency, "groupId");
            if (!isJackson2Group(groupId)) {
                continue;
            }
            String artifactId = text(dependency, "artifactId");
            String type = text(dependency, "type");
            String scope = text(dependency, "scope");
            if ("pom".equals(type)) {
                // A BOM pins versions for artifacts declared elsewhere; it is not
                // itself on the classpath, so it cannot reintroduce Jackson 2.
                continue;
            }
            violations.add(groupId + ":" + artifactId
                    + (scope.isEmpty() ? "" : " (" + scope + ")")
                    + " in " + location(dependency));
        }

        assertTrue(violations.isEmpty(), () -> """
                The service runs on Jackson 3 (tools.jackson), and springdoc is the only reason \
                Jackson 2 jars are on the classpath at all. A jackson 2 dependency declared here \
                would pin that mapper on purpose. Use the tools.jackson coordinate (or drop the \
                dependency) instead:
                  """ + String.join("\n  ", violations));
    }

    private static void collect(Path file, List<String> violations) {
        try {
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
                if (imported.startsWith(JACKSON_2) && !imported.startsWith(SHARED_ANNOTATIONS)) {
                    violations.add(file + ":" + (i + 1) + " -> " + imported);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** True for every Jackson 2 group: core, datatype, dataformat, module, jaxrs, jr, the BOM. */
    private static boolean isJackson2Group(String groupId) {
        return groupId.equals(JACKSON_2_GROUP) || groupId.startsWith(JACKSON_2_GROUP + ".");
    }

    /** Where the dependency sits, so the failure names the section to edit. */
    private static String location(Element dependency) {
        Node dependencies = dependency.getParentNode();
        Node parent = dependencies == null ? null : dependencies.getParentNode();
        if (parent instanceof Element element) {
            switch (element.getTagName()) {
                case "dependencyManagement":
                    return "<dependencyManagement>";
                case "profile": {
                    String id = text(element, "id");
                    return id.isEmpty() ? "a profile" : "profile '" + id + "'";
                }
                default:
                    break;
            }
        }
        return "the project's <dependencies>";
    }

    private static Document readPom() throws IOException {
        Path pom = Path.of("pom.xml");
        assertTrue(Files.isRegularFile(pom),
                "pom.xml must be readable from the test working directory: " + Path.of("").toAbsolutePath());
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // The pom is local, but a parser that follows a doctype can still be
            // talked into reading a file or a URL; this gate needs neither.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(pom.toFile());
        } catch (Exception e) {
            throw new IOException("cannot parse " + pom.toAbsolutePath(), e);
        }
    }

    /** The text of a direct child element, or "" when it is absent. */
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
