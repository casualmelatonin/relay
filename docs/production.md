# Production setup

Use Java 21 or 25 and PostgreSQL. Set `SPRING_PROFILES_ACTIVE=prod` and provide `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. No identity provider is required.

Relay is a shared workspace without application authentication. Anyone who can reach it can read and change workspace data. Deploy it on a trusted private network or behind access controls managed by your hosting environment.

The Dockerfile builds a JAR and runs it as an unprivileged user. `compose.yml` connects it to PostgreSQL, requires the database password via the environment, and publishes the app on loopback. Use an HTTPS reverse proxy for remote access. Only a trusted proxy should reach the application, because forwarded headers are honored to derive the external request origin. Configure request/body limits and timeouts at that proxy.

Flyway migrations run at startup; Hibernate validates their resulting schema. Use a separate migration identity if your deployment policy separates schema ownership from application DML. Database backups and restore drills are operational requirements outside this repository.

Only health and info endpoints are exposed by Actuator. Metrics remain internal. Browser mutations require the same origin when an Origin header is supplied. Logs and responses include correlation IDs; request bodies are not logged by the application filter.

Run `./mvnw verify` with Docker available before deployment so the PostgreSQL migration test executes. H2 verification does not replace PostgreSQL validation.
