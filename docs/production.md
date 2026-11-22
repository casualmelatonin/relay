# Production setup

Use Java 21 or 25 and PostgreSQL. Set `SPRING_PROFILES_ACTIVE=prod` and provide `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, and optionally `OIDC_AUDIENCE` (default `relay`). No production credential is stored in source.

The production profile uses OIDC login for browser users and JWT validation for stateless API/MCP clients, including issuer/audience checks. Configure your identity provider to issue `relay.read`/`relay.write` scopes and the `relay_roles` claim for administrators. Register the browser redirect URI `/login/oauth2/code/relay` under the deployed origin.

The Dockerfile builds a JAR and runs it as an unprivileged user. `compose.yml` connects it to PostgreSQL and requires credentials via environment variables. Run it behind an HTTPS reverse proxy: production cookies are Secure. Only a trusted proxy should reach the application, because forwarded headers are honored to derive the external request origin. Configure request/body limits and timeouts at that proxy.

Flyway migrations run at startup; Hibernate validates their resulting schema. Use a separate migration identity if your deployment policy separates schema ownership from application DML. Database backups and restore drills are operational requirements outside this repository.

Only health probes are public. Metrics/Prometheus endpoints require administrator access. Logs and responses include correlation IDs, but credentials and request bodies are not logged by the application filter.

Run `./mvnw verify` with Docker available before deployment so the PostgreSQL migration test executes. The local environment used to build this project lacked Docker, so that test was skipped locally. Production OIDC integration requires verification against your own identity provider; sample local credentials do not validate that integration.
