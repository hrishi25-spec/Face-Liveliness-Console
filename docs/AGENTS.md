# Documentation guidance

These rules apply to documentation in this directory. Follow the relevant source project's `AGENTS.md` when changing implementation code.

- Keep instructions aligned with the current source, configuration, and available build/test commands. Check the referenced files before documenting behavior as implemented.
- Clearly distinguish implemented behavior, integration guidance, known limitations, and future work. Mark assumptions that have not been validated against a host MOSIP client.
- Keep API paths, request/response examples, configuration names, defaults, security behavior, and version requirements consistent with their owning project. Update related docs when those contracts change.
- Use synthetic values in examples. Never include live credentials, private keys, production identifiers, or real biometric data.
- Prefer links to the owning source or authoritative project documentation over duplicating long implementation details.
