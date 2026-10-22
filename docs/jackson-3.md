# Jackson 3 rules

Spring Boot 4 serialises with Jackson 3 (`tools.jackson`), and this service's
own code must use it too. Nothing in the build forces that, which is why the
rules are written down: springdoc 3.x still renders `/v3/api-docs` through
swagger-core 2.2.x, which is Jackson 2 based, so `jackson-databind` 2.21.5 stays
on the compile and runtime classpath. A `com.fasterxml.jackson` import therefore
**compiles** while the application actually runs on a different mapper. That is
how the JPA JSON converters and `LivenessHttpClient` once ended up pinned to a
springdoc transitive, and Boot 4 then changed the mapper underneath them: the
failure was at runtime, not at compile time.

## Rules

| | Use | Not this |
|---|---|---|
| Packages | `tools.jackson.databind.*`, `tools.jackson.core.*` | `com.fasterxml.jackson.core.*`, `com.fasterxml.jackson.databind.*` |
| Annotations | `com.fasterxml.jackson.annotation.*` (`@JsonInclude`, `@JsonProperty`, …) | — this one package is shared, see below |
| Mapper instances | `JsonMapper.shared()` when nothing needs configuring, otherwise `JsonMapper.builder()…build()` | `new ObjectMapper()` followed by `mapper.configure(…)` — Jackson 3 mappers are immutable and `configure()` is gone |
| Failures | catch `tools.jackson.core.JacksonException` | `JsonProcessingException` — deleted in Jackson 3; the replacement is unchecked, so the `catch` clause no longer needs the method to declare `IOException` |
| Renamed or folded APIs | `JsonNode.propertyNames()`; `java.time` support ships in databind, so no module registration | `fieldNames()`, `registerModule(new JavaTimeModule())` |
| Boot properties | `spring.jackson.*` binds to Jackson 3 | `spring.jackson.serialization.write-dates-as-timestamps` — the feature was removed, instants are always ISO-8601 |

**Annotations are the exception, and it is deliberate.** Jackson 3's
`jackson-databind` depends on `jackson-annotations` 2.x, so
`com.fasterxml.jackson.annotation` is the annotation API *both* mappers read.
The DTOs' `@JsonInclude` imports are correct; changing them would break Jackson
3.

**Add a mapper only where one is needed.** A converter or client that is
stateless uses `JsonMapper.shared()`; a component that must deviate (for
example `EffectivePolicyJsonConverter` tolerating unknown fields so an older row
still loads after a field is added) builds its own. Per-call `new JsonMapper()`
is wasted work and tends to grow a divergent configuration.

**Do not add a Jackson 2 dependency.** It is already present because
swagger-core needs it. If a swagger or OpenAPI problem tempts you to pin,
upgrade or configure Jackson 2, the fix belongs in springdoc's configuration,
not in this service's code or its dependency tree.

## What enforces it

`JacksonRuntimeGateTest` fails on any `com.fasterxml.jackson` import under
`src/main` and `src/test` — core, databind, datatype, dataformat, module — allows
only `com.fasterxml.jackson.annotation`, and reports `file:line -> symbol`. When
it fails, change the import — do not widen the allowed list.

The same test reads `pom.xml` and fails on any Jackson 2 coordinate declared
there — `com.fasterxml.jackson.core:jackson-databind`, a `dataformat-*` module,
a test-scoped `jackson-module-*` — naming the artifact and the section it sits
in (`<dependencyManagement>`, a named profile, or the project's
`<dependencies>`). An import is one line to fix; a declared dependency changes
what the module and every artifact repackaged from it resolve against. An
imported BOM (`<type>pom</type>`) is allowed because it pins versions for
artifacts declared elsewhere and puts no jar on the classpath.

Today Jackson appears in three places: the JPA converters in
`io.mosip.liveness.models.converter`, the error-body parsing in
`LivenessHttpClient`, and the annotations on the DTOs. The wire format is
unchanged by any of these rules: instants stay ISO-8601 strings and the
`@JsonInclude` policy still governs what is emitted.
