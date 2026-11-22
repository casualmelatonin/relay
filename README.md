# Relay

Relay helps a developer build a large project in small, understandable chunks with an AI assistant. It combines a project plan, focused ticket details, and a shared coordination protocol for agents.

The intended loop is **define → break down → understand → build → review → continue**. Agent assignment supports that loop; it does not launch an agent or generate an entire application.

## Run locally

Requirements: Java 21 or 25. Maven 3.9.11 is provided by the wrapper. No Node installation is needed.

```sh
./mvnw spring-boot:run
```

Open http://localhost:8080. The development profile binds to loopback and uses an H2 database in `data/`. Example projects, profiles, and learning guides are created on the first launch of an empty database.

The UI, REST API, and MCP endpoint open directly without accounts, passwords, or authentication headers. All clients share the same workspace.

```sh
./mvnw verify
./mvnw spotless:apply
./mvnw package
java -jar target/relay-coordination-1.0.0-SNAPSHOT.jar
```

The preview used during development may run on port 5173; `--server.port=5173` selects that port explicitly.

## Develop a project incrementally

1. Create a **project** with a clear outcome and scope.
2. Add a small number of **milestones**, each describing a useful deliverable.
3. Split the first milestone into **chunks** small enough to explain, build, and verify independently.
4. Open a ticket. Describe **what to build** and its **definition of done**.
5. Use your AI assistant to explain the relevant code and implement that focused change. An agent can claim a Ready chunk through MCP or the REST API.
6. Review the changed code and verification result. Attach a result and mark the ticket Done.
7. Continue to the next useful chunk. Revise the plan as understanding improves.

Projects, milestones, stories, and chunks map to stable API enum values `INITIATIVE`, `EPIC`, `STORY`, and `TASK`. Existing agent tooling can use those values. Saved learning guides remain available through REST/MCP for clients that use them; the ticket page focuses on the work and its result.

## Project ticket prefixes

Set a unique **Ticket prefix** when creating a project, such as `HTTP`. The project starts at `HTTP-1`; every milestone, story, and task in that project takes the next number from its own persistent sequence. Another project starts at 1 under its own prefix. Prefixes accept 2–12 letters/digits, starting with a letter, and are normalized to uppercase. Duplicate prefixes are rejected. A blank prefix on creation selects the next available `RLY` prefix.

Standalone work uses the reserved `WORK` prefix. Renaming a project prefix preserves its ticket numbers. Moving a ticket or milestone tree into another project allocates fresh numbers from the destination sequence; vacated numbers are not reused. Database IDs, URLs, dependencies, and saved guides remain intact. Existing projects are migrated in creation order; any duplicate legacy prefixes receive numeric suffixes. Historical activity text is not rewritten.

REST and MCP create/update requests accept optional `keyPrefix` on `INITIATIVE` items; read responses include the effective prefix. Omit it on child work and when preserving the existing setting.

## Appearance

The workspace uses a dark theme, including navigation, forms, lists, boards, and status badges. Shared colors live in `static/css/tokens.css`; feature styles use those tokens. Native form controls follow the same dark color scheme.

## Code map

| Module | Responsibility |
| --- | --- |
| `web` | Read-only page routes, feature-specific form actions, view models, and presentation formatting |
| `chunk` | Learning guides, implementation steps, reflections, and their optimistic concurrency |
| `service` | Transactional coordination operations and an isolated completion graph policy |
| `domain` | Work types, states, priorities, and domain errors |
| `persistence` | JPA entities and Spring Data repositories |
| `api` | Versioned REST endpoints, validated request records, and Problem Details |
| `mcp` | Stateless JSON-RPC transport, tool schemas, and a separate tool executor |
| `security` | Browser-origin checks, response headers, and request correlation |
| `config` | Clock and development-only example data |
| `templates/workspace` | One template per feature, plus shared layout and learning guide fragments |
| `static/css` | Design tokens, shell, reusable components, and feature layouts |

See [architecture](docs/architecture.md), [API and agent guide](docs/agents.md), and [production setup](docs/production.md).

## Verification and current boundaries

Integration tests exercise real Spring MVC, Flyway, JPA, graph validation, optimistic versions, claim races, leases, MCP tools, page rendering, and learning guide edits. A PostgreSQL Testcontainers migration test runs when Docker is available; it is skipped when Docker is unavailable. H2 verification does not replace PostgreSQL validation in deployment CI.

This is a single shared workspace, bounded to 2,000 work items and 500 registered agent profiles. Mutations acquire a database workspace lock so multiple app replicas cannot make conflicting graph or claim decisions. This deliberately favors understandable correctness over high-throughput multi-tenant scheduling. Activity is append-only at the application boundary; workspace snapshots expose the latest 200 events.

The application supplies REST/MCP tools, not an embedded AI model or agent runtime. Connect your own assistant to propose a breakdown and work through it with the developer. Learning notes support review; they do not prove the developer understood a change. There is currently no enforced human approval gate between chunks.
