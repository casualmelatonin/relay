# API and AI assistant guide

The application exposes JSON REST endpoints under `/api/v1` and a stateless HTTP JSON-RPC MCP endpoint at `/mcp`. Use JSON request bodies, numeric item IDs, UUID agent IDs, and uppercase domain enum values.

Production clients use a bearer JWT from the configured OIDC issuer, with `relay.read` and/or `relay.write` scopes. `relay_roles: ["ADMIN"]` grants workspace administration. The JWT subject must match the registered agent profile's subject. A caller cannot act as another subject's agent by supplying its ID.

Local development supports HTTP Basic using the separate `operator` and `agent` accounts documented in the README. Browser UI writes require the administrator role and CSRF tokens; the stateless API checks scopes, origin, principal ownership, versions, and execution proofs.

## Plan in chunks

Ask your AI assistant to propose milestones and small chunks, each with an explanation and concrete verification. Use `create_item` for records, then `update_chunk_guide` for purpose, learning goal, small steps, and reflection. Review the plan with the developer before implementing it. The app does not automatically call an AI model.

MCP tools:

- Read: `list_work`, `get_item`, `get_chunk_guide`, `list_agents`, `get_activity`.
- Plan: `create_item`, `update_item`, `update_chunk_guide`, `register_agent`, `add_comment`.
- Execute: `claim_task`, `release_task`, `heartbeat`.

`initialize`, `tools/list`, `tools/call`, and `ping` are supported. Tool failures return `isError: true`; successful results are JSON in text content blocks. The transport accepts JSON responses and does not require a long-lived server session.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/workspace` | Shared items, profiles, and recent activity |
| GET/POST | `/api/v1/items` | List or create work |
| GET/PATCH | `/api/v1/items/{id}` | Read or update work |
| POST | `/api/v1/items/{id}/claim` | Claim with `agentId` and current `version` |
| POST | `/api/v1/items/{id}/release` | Release with `agentId`, `version`, `claimToken` |
| POST | `/api/v1/items/{id}/comments` | Append a note using `body` |
| GET/PUT | `/api/v1/items/{id}/guide` | Read or replace learning context using its own `version` |
| GET/POST | `/api/v1/agents` | List or register profiles |
| POST | `/api/v1/agents/{id}/heartbeat` | Renew current claims with `claims: [{itemId, claimToken}]` |

Work updates use flat fields, not a nested `fields` object. Omitted fields preserve existing values. `clearParent: true` and `clearAssignee: true` explicitly clear relationships. Versions are required and cannot be omitted even on a version-zero item.

Executable updates from agents need the active claimant ID, current claim token, and current item version. Completion also requires `output`, finished prerequisites, and finished child work. Assignment does not acquire a claim. Administrators can review/change work without pretending to be a running agent, but cannot reassign a live claim before releasing it.
