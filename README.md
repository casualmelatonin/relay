# Relay

Relay helps a developer build a large project in small, understandable chunks with an AI assistant. It combines a project plan, a learning guide for every chunk, and a shared coordination protocol for agents.

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
4. Open a chunk. Record **why it matters**, **what you should learn**, **small implementation steps**, and a **definition of done**.
5. Use your AI assistant to explain the relevant code and implement that focused change. An agent can claim a Ready chunk through MCP or the REST API.
6. Review the changed code and verification result. Attach a result, record what you learned, and mark the chunk Done.
7. Continue to the next useful chunk. Revise the plan as understanding improves.

Projects, milestones, stories, and chunks map to stable API enum values `INITIATIVE`, `EPIC`, `STORY`, and `TASK`. Existing agent tooling can use those values.

## Project ticket prefixes

Set **Ticket prefix** when creating or editing a project (for example, `HTTP`). Milestones and chunks inherit the project prefix, producing identifiers such as `HTTP-18`. Prefixes accept 2–12 letters/digits starting with a letter and are normalized to uppercase; blank uses `RLY`. Existing projects and standalone work retain `RLY` until configured. Numeric IDs, URLs, dependencies and learning guides are unchanged. Reparented work inherits its new project’s prefix. Historical activity text is not rewritten.

REST and MCP create/update requests accept optional `keyPrefix` on `INITIATIVE` items; read responses include the effective prefix. Omit it on child work and when preserving the existing setting.

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
