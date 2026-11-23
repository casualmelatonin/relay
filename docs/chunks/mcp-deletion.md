# MCP deletion

## Outcome, scope, and definition of done

External agents discover and invoke `delete_item` and `delete_agent` through MCP. Deletion removes one eligible record, rejects broken relationships and stale item versions, and preserves activity. Done means the schemas, dispatcher, application rules, migration, onboarding guide, and verification agree. Subtree deletion, history deletion, and new UI or REST deletion controls are outside this chunk.

## Why this matters and what to learn

Agents need to clean up obsolete planning and unused profiles. Deleting a record is a graph change: children, prerequisites, claims, and assignments can still reference it. Learn how transaction boundaries and database constraints protect those relationships, and why audit references may outlive the live entity.

## Small steps

1. Trace tool discovery, input validation, and the shared coordination service.
2. Check current version and references while holding the same workspace lock as other mutations.
3. Reject children, incoming prerequisites, unreleased claims, and profiles still in use.
4. Cascade only owned guide/label/outgoing dependency data. Preserve activity IDs and reserve historical ticket keys.
5. Verify real MCP calls, rejected deletions, expired claims, retained history, and database upgrades.

## Review notes

Deletion is permanent and explicit; remove work bottom-up rather than silently cascading a project. Expired claims still need release proof. Agent deletion removes a registry record without stopping a runtime. V5 discovers the database-generated foreign-key names so both H2 and PostgreSQL retain immutable activity while cascading owned chunk guides. No old audit rows are rewritten. Run `./mvnw verify` and the Docker-backed PostgreSQL migration test before deployment.
