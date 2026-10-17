# Java service and shared engine

These rules apply to the root Maven project's `src/main` and `src/test` trees.

- Target Java 17, as configured in the root `pom.xml`; use the Maven wrapper so local and CI Maven versions match.
- Keep REST contracts, validation, and OpenAPI behavior aligned when changing controllers or DTOs. User-facing liveness failures must remain generic and must not expose model internals.
- Keep liveness decisions independent of WAN availability and camera/vendor APIs. Device integrations should use the existing adapters and frame interfaces.
- For persistence changes, update the Flyway schema and relevant tests. PostgreSQL is the production database; an H2-backed test alone does not establish PostgreSQL compatibility.
- Run the full suite with `./mvnw --batch-mode test`. For a focused change, use `./mvnw -Dtest=TestClassName test`, then run the full suite when practical. PostgreSQL/Testcontainers checks may require Docker.
- Android embedding sources under `android_client/` are outside this Maven source tree and are not compiled by the root build; follow that directory's integration guidance.
