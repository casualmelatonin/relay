# API and AI assistant guide

The application exposes JSON REST endpoints under `/api/v1` and a stateless HTTP JSON-RPC MCP endpoint at `/mcp`. Use JSON request bodies, numeric item IDs, UUID agent IDs, and uppercase domain enum values.

No login, bearer token, or HTTP Basic credentials are required. All clients share workspace access. Agent `subject` is an optional runtime label, not an authenticated identity; it defaults to `workspace` when omitted. Activity uses the shared `workspace` actor and is not proof of who performed an action.

Browser-origin checks, work-item versions, execution lease tokens, and dependency rules still apply. Claim tokens coordinate current execution; they are not account credentials.

## Start here

Relay stores plans and coordinates work. Your assistant/runtime chooses tools, edits source files, and runs verification using its own capabilities. Registering a profile or assigning a ticket does not launch a process. Relay does not automatically call an AI model.

Before creating records, inspect existing work with `list_work` and read relevant items with `get_item`. Reuse the project's existing hierarchy instead of creating a duplicate plan. Discover the running server's current tool definitions with `tools/list`; this guide explains how to use them together.

Repository agents can find this guide through `AGENTS.md`. External assistants should call `get_agent_guide` with no arguments after discovering tools. Initialization instructions point to this tool, which returns this full document without requiring repository access. It returns an object with `format: "markdown"` and `content` containing the guide, serialized in the usual MCP text block.

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_agent_guide","arguments":{}}}
```

## What to create

Use the smallest useful plan. Start with a project outcome, a few milestones, and detailed chunks for the first milestone. Expand later milestones as understanding improves.

| UI concept | API `type` | What belongs here | Allowed parent when supplied |
| --- | --- | --- | --- |
| Project | `INITIATIVE` | Overall outcome and scope; optional unique ticket prefix | None |
| Milestone | `EPIC` | A useful deliverable that groups related work | `INITIATIVE` |
| Story | `STORY` | An optional grouping of work around a user capability | `EPIC` |
| Chunk / task | `TASK` | One focused change with concrete verification | `EPIC`, `STORY`, or `TASK` |

Parentless work is supported. A project need not contain stories: milestones can contain tasks directly. Only stories and tasks can hold execution claims or learning guides.

For each chunk, fill in:

- `title`: a concrete outcome, such as "Validate the task search query".
- `description`: what to build, its scope, and relevant existing behavior.
- `acceptance`: observable definition of done, including verification.
- `parentId`: the containing milestone, story, or task, using its numeric ID.
- `dependencies`: numeric IDs of prerequisites that must finish first; omit when independent. Parent links already make parents wait for children, so do not add a parent as its child's prerequisite.

Then use `update_chunk_guide` to capture why it matters (`purpose`), the concept being learned (`learningGoal`), small implementation steps (`steps`), and review notes (`reflection`). Guides belong to stories/tasks and have their own versions. Review notes support discussion; they do not prove understanding.

Review the plan with the developer before implementation. This is a collaboration practice; Relay currently has no enforced human approval gate between chunks.

## MCP connection and messages

With the default local server running (`./mvnw spring-boot:run`), configure your assistant's MCP client to use `http://localhost:8080/mcp`. Adjust the host/port for your deployment. Send `Content-Type: application/json` and accept JSON responses.

Each operation is a separate HTTP POST to `/mcp`. HTTP connections may be reused, but there is no required long-lived MCP session. Work, profiles, and leases persist in the database between requests.

The normal connection flow is `initialize` → `tools/list` → repeated `tools/call` requests. `ping` and `server/discover` are also supported. Relay advertises protocol versions `2026-07-28`, `2025-06-18`, and `2025-03-26`; inspect the version returned by initialization.

Example initialization body:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": { "name": "my-assistant", "version": "1.0" }
  }
}
```

Discover tools with a new request:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

The response contains names, descriptions, and JSON input schemas. Discovery returns available operations; retrieve actual project data through the read tools. The assistant chooses a tool and arguments, its client sends the call, and Relay validates it through the same application services used by REST and the UI.

For example, find ready tasks with finished dependencies and no live claim:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "list_work",
    "arguments": { "type": "TASK", "availableOnly": true }
  }
}
```

Successful tool results are JSON serialized inside text content blocks:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{ "type": "text", "text": "[]" }],
    "isError": false
  }
}
```

Here, `[]` means no matching work. Parse the text as JSON to use successful results. Tool failures return `result.isError: true` with a text message such as `version_conflict: ...`. Protocol errors use the top-level `error` field. Check both instead of treating HTTP 200 as success. Calls that need results must include a JSON-RPC `id`; messages without an ID are acknowledged with HTTP 202 and do not execute tools.

## Complete MCP tool reference

All tool arguments are flat JSON objects. Supply only documented fields. Numeric item IDs differ from display keys such as `HTTP-7`; `agentId`, `assigneeId`, and `claimToken` are UUID strings. Returned item records include the current `version`, claim information, and work context.

| Tool | Required arguments | Optional arguments and behavior |
| --- | --- | --- |
| `get_agent_guide` | None | Read this onboarding guide before planning or executing work. Returns `format: "markdown"` and `content`. No workspace changes or agent registration required. |
| `list_work` | None | `type`, `status`, `assigneeId`, `parentId`, `query`, `availableOnly`. Filters combine. Query searches key/title/description. Available means `READY`, dependencies done, and no live claim; assignment and executable type still need checking before claiming. |
| `get_item` | `id` | Read current work context, claim information, and version. |
| `get_chunk_guide` | `id` | Read the guide and its independent version; version `-1` means no guide exists. |
| `update_chunk_guide` | `id`, `version` | `purpose`, `learningGoal`, `steps`, `reflection`. Use the current guide version, or `-1` for a new guide. Send the full intended content: omitted guide text fields are cleared. |
| `create_item` | `title`, `type` | `keyPrefix`, `parentId`, `priority`, `assigneeId`, `description`, `acceptance`, `labels`, `dependencies`. Creates in `BACKLOG`; default priority is `MEDIUM`. |
| `update_item` | `id`, `version` | `title`, `keyPrefix`, `parentId`, `clearParent`, `status`, `priority`, `assigneeId`, `clearAssignee`, `description`, `acceptance`, `output`, `labels`, `dependencies`, `agentId`, `claimToken`. Type cannot be changed. Claimed work requires current execution proof. |
| `delete_item` | `id`, `version` | Permanently delete one item and its guide, labels, and outgoing dependency links. Requires no children, incoming dependencies, or unreleased claims (including expired claims). Activity remains; ticket keys stay reserved. Returns `{ "id": 42, "deleted": true }`. |
| `delete_agent` | `agentId` | Permanently delete an unused profile after releasing claims and clearing/changing every assignment. Activity remains. Does not stop a runtime process. Returns `{ "agentId": "<UUID>", "deleted": true }`. |
| `register_agent` | `name`, `role` | `subject` (runtime label), `color` (`#RRGGBB`). Names are unique ignoring case. Retain the returned profile UUID; reuse your own profile on later calls. |
| `claim_task` | `id`, `agentId`, `version` | Atomically claims a `READY` or `IN_PROGRESS` story/task for 15 minutes, assigns it to the claimant, and sets `IN_PROGRESS`. Returns the current item and token. |
| `release_task` | `id`, `agentId`, `version`, `claimToken` | Clears your claim; `IN_PROGRESS` becomes `READY`. Assignment remains. Valid proof can release an expired claim too. |
| `heartbeat` | `agentId`, `claims` | `claims` is an array of `{ "itemId": 42, "claimToken": "<UUID>" }`. Include every active claim held by this profile. An empty array is presence only when it has no active claims. Returns agent data, not updated item versions. |
| `add_comment` | `id`, `body` | Append progress, blockers, or handoff context. |
| `list_agents` | None | Read registered profiles and heartbeat-derived presence. Presence does not independently verify a running process. |
| `get_activity` | None | `itemId`, `limit` (1–200; default 50). Reads from the latest 200 workspace events, then filters; this is not a full-history query. |

Values and limits:

- Types: `INITIATIVE`, `EPIC`, `STORY`, `TASK`.
- Statuses: `BACKLOG`, `READY`, `IN_PROGRESS`, `REVIEW`, `DONE`, `BLOCKED`.
- Priorities: `URGENT`, `HIGH`, `MEDIUM`, `LOW`.
- Item title: 1–240 characters. Description, acceptance, output, and each guide field: up to 8,000 characters. Labels and dependencies: up to 50 each; each label is nonblank and up to 100 characters.
- `keyPrefix`: project only, 2–12 letters/digits starting with a letter, normalized to uppercase and unique. Blank creation selects an available `RLY` prefix; omit it to preserve an existing prefix on update. Children inherit the project prefix. Standalone work uses `WORK`.
- Updates preserve omitted item fields. `labels` and `dependencies` replace their entire collections; send `[]` to clear. Use `clearParent: true` or `clearAssignee: true` to clear a relationship, without supplying its replacement in the same call.

## Worked planning example

Each entry below is the `params` object inside its own `tools/call` request. Replace example IDs with IDs returned by your server; do not assume sequential IDs.

Create a project:

```json
{"name":"create_item","arguments":{"title":"Searchable task workspace","type":"INITIATIVE","keyPrefix":"SEARCH","description":"Help developers find work by title and description.","acceptance":"A developer can search tasks and inspect a matching ticket."}}
```

Create a milestone using the returned project ID (illustrated as `10`):

```json
{"name":"create_item","arguments":{"title":"Basic task search","type":"EPIC","parentId":10,"description":"Deliver a working text search for tasks."}}
```

Create one focused chunk using the returned milestone ID (illustrated as `11`):

```json
{"name":"create_item","arguments":{"title":"Validate the task search query","type":"TASK","parentId":11,"description":"Read the existing search flow, then define and implement blank-query behavior. Keep presentation changes for another chunk.","acceptance":"Blank input follows the documented behavior, existing searches still work, and ./mvnw verify passes."}}
```

Read the returned task's guide with `get_chunk_guide`. If its ID is `12` and guide version is `-1`, save:

```json
{"name":"update_chunk_guide","arguments":{"id":12,"version":-1,"purpose":"Make search input behavior predictable.","learningGoal":"Understand input validation and its application boundary.","steps":"1. Explain the current search flow.\n2. Agree blank-query behavior.\n3. Make a focused change.\n4. Verify and review it.","reflection":"Review how input reaches the search service and discuss the verification evidence."}}
```

## Execute, verify, and hand off

1. Use `list_agents` to find your existing profile or `register_agent` to create one. Reuse only a profile belonging to your runtime; its UUID coordinates claims but is not an authenticated identity.
2. Read the item and guide. Explain the existing code flow, intended outcome, scope, and definition of done to the developer before editing.
3. Once the chunk is ready to execute, move it from `BACKLOG` to `READY` with `update_item`, supplying its current item version. Creation does not make work Ready automatically.
4. Call `claim_task` with the returned current version and your profile UUID. Prerequisites must be Done; a live claim or assignment to another profile blocks the claim. At most 100 active claims are allowed per profile. Retain `claimToken`, `leaseUntil`, and the returned version.
5. Implement the focused change using your runtime's file and command tools. Call `heartbeat` before expiry with every active claim and its token. Heartbeats change item versions: call `get_item` again before updating or releasing after a heartbeat.
6. Record blockers/progress with `add_comment`. For a handoff, leave context, read the latest version, and call `release_task` before requesting reassignment.
7. Run relevant verification (`./mvnw verify` for Relay changes; PostgreSQL migration tests with Docker before deployment). Read the current item, then use `update_item` with `agentId`, `claimToken`, `version`, `output`, and `status: "REVIEW"`. Output should describe the artifact/change, checks run, results, and remaining limitations. Review does not clear the lease; renew it while holding the claim or release it for handoff.
8. After review, mark the item `DONE` using its latest version and active claim proof if still claimed. Stories/tasks require nonblank output, all prerequisites must be Done, and parents require all children Done. Successful completion clears the claim. Save guide review notes using the current guide version and all intended guide fields.

Read returned versions after each mutation. Do not calculate the next version or reuse an example version. Item and guide versions are independent.

## Delete obsolete work or profiles

Call `get_item` first and supply its returned `version` to `delete_item`:

```json
{"name":"delete_item","arguments":{"id":42,"version":3}}
```

Deletion is permanent. Remove incoming dependencies and delete or move children first; there is no subtree deletion. Release any claim with its current proof before deletion, even if the lease expired. The item's learning guide, labels, and outgoing dependency links are deleted together. Prior activity and a new deletion event keep the original numeric item ID, so `get_activity` can still filter recent events for a deleted item. Deleted project prefixes and all allocated ticket numbers stay reserved to avoid reusing historical keys.

For an agent profile, release its claims, clear or change its assignments with `update_item`, then call:

```json
{"name":"delete_agent","arguments":{"agentId":"<profile UUID>"}}
```

A repeated delete returns `not_found`; after an uncertain network result, inspect current state before retrying. Only work-item deletion uses an optimistic version: agent profiles have no version and deletion checks all current references under the workspace lock. These tools use the same shared-workspace access model as other mutations; profile UUIDs are not authentication credentials.

## Recover from rejected calls

| Error / situation | Next action |
| --- | --- |
| `version_conflict` | Read the item or guide again, reconcile your intended change with the current content, then retry with its actual version. |
| `already_claimed`, `assigned_elsewhere`, `active_claim` | Inspect current assignment/claim. Choose other work or arrange release and reassignment. Listing available work does not guarantee your claim will win. |
| `stale_claim`, expired lease | Read current work. Reacquire with `claim_task` if eligible or release your expired claim with its proof. An expired token cannot authorize updates and heartbeat cannot revive it. |
| `incomplete_claim_proofs` | Supply proofs for every active claim held by that profile in one heartbeat. |
| `unfinished_dependencies`, `unfinished_children`, `graph_cycle` | Inspect prerequisite and parent links; finish required work or correct the plan without introducing a completion deadlock. |
| `item_has_children`, `item_is_prerequisite` | Move/delete children or remove incoming dependency links before deletion. No related work is deleted automatically. |
| `claim_requires_release` | Release the current claim using its proof, including an expired claim, then read the new version before deletion. |
| `agent_in_use` | Release claims and clear/change all assignments before deleting the profile. |
| `output_required` | Supply nonblank completion evidence before closing a story/task. |
| `validation_failed`, `invalid_arguments` | Compare arguments with `tools/list`; check required values, types, enums, and lengths. |
| Uncertain outcome after a network failure | Read current state/activity before retrying a mutation, especially creation or registration, to avoid duplicates. |

For maintainers: schemas live in `mcp/ToolCatalog.java`, dispatch in `mcp/McpToolExecutor.java`, and JSON-RPC handling in `mcp/McpController.java`. Coordination rules live in application services/policies. Keep this guide aligned when the tool contract changes. Maven bundles this file as `relay/agents.md` on the classpath; `AgentGuide` loads it at startup. Rebuild and restart after changing the guide so external agents receive the updated content.

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

Updates to claimed work need the active claimant ID, current claim token, and current item version. Unclaimed work can be edited and reviewed directly. Completion also requires `output`, finished prerequisites, and finished child work. Assignment does not acquire a claim. A live claim must be released before reassignment. Expired claims must be reacquired or released before further updates.
