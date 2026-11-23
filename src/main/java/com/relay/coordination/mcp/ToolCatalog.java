package com.relay.coordination.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit JSON schemas keep the agent contract stable independently of persistence models. */
public final class ToolCatalog {
  private ToolCatalog() {}

  private static final Map<String, Object> TEXT = Map.of("type", "string");
  private static final Map<String, Object> ID = Map.of("type", "integer", "minimum", 1);
  private static final Map<String, Object> VERSION = Map.of("type", "integer", "minimum", 0);
  private static final Map<String, Object> UUID = Map.of("type", "string", "format", "uuid");

  public static List<Map<String, Object>> tools() {
    Map<String, Object> create =
        fields(
            "title",
            TEXT,
            "type",
            enumeration("INITIATIVE", "EPIC", "STORY", "TASK"),
            "keyPrefix",
            Map.of(
                "type",
                "string",
                "maxLength",
                12,
                "description",
                "Project ticket prefix (INITIATIVE only); child work inherits it. Blank resets to RLY. Numeric IDs remain unchanged."),
            "parentId",
            ID,
            "priority",
            enumeration("URGENT", "HIGH", "MEDIUM", "LOW"),
            "assigneeId",
            UUID,
            "description",
            TEXT,
            "acceptance",
            TEXT,
            "labels",
            array(TEXT),
            "dependencies",
            array(ID));
    Map<String, Object> update = new LinkedHashMap<>(create);
    update.remove("type");
    update.putAll(
        fields(
            "id",
            ID,
            "version",
            VERSION,
            "status",
            enumeration("BACKLOG", "READY", "IN_PROGRESS", "REVIEW", "DONE", "BLOCKED"),
            "clearParent",
            Map.of("type", "boolean"),
            "clearAssignee",
            Map.of("type", "boolean"),
            "output",
            TEXT,
            "agentId",
            UUID,
            "claimToken",
            UUID));
    return List.of(
        tool(
            "get_agent_guide",
            "Read this first before planning or executing work. Returns Relay's onboarding guide as Markdown in content, with format=markdown: what to create, all MCP tools, examples, claims, verification, and handoffs. No repository access required.",
            Map.of()),
        tool(
            "list_work",
            "Read shared work, optionally filtering type, status, assigneeId, parentId, query, or availableOnly. Available means ready with finished dependencies and no live claim. Assignment does not launch an agent.",
            fields(
                "type",
                create.get("type"),
                "status",
                update.get("status"),
                "assigneeId",
                UUID,
                "parentId",
                ID,
                "query",
                TEXT,
                "availableOnly",
                Map.of("type", "boolean"))),
        tool(
            "get_item",
            "Read current work context and version before updating or claiming.",
            fields("id", ID),
            "id"),
        tool(
            "get_chunk_guide",
            "Read why a chunk matters, what the developer should learn, small implementation steps, and reflection notes. Guide version -1 means no guide exists yet.",
            fields("id", ID),
            "id"),
        tool(
            "update_chunk_guide",
            "Write a developer learning guide for one small chunk: purpose, learningGoal, steps, reflection. Read first and supply current guide version (-1 when new). Explain the work before building; keep it focused and reviewable.",
            fields(
                "id",
                ID,
                "version",
                Map.of("type", "integer", "minimum", -1),
                "purpose",
                TEXT,
                "learningGoal",
                TEXT,
                "steps",
                TEXT,
                "reflection",
                TEXT),
            "id",
            "version"),
        tool(
            "create_item",
            "Create work in Backlog. Initiatives contain epics; epics contain stories/tasks; tasks can contain subtasks.",
            create,
            "title",
            "type"),
        tool(
            "update_item",
            "Update using the current version. Claimed work needs its current agentId and claimToken for execution progress/output. Complete prerequisites and child work first; include completion evidence. Use explicit clearParent/clearAssignee flags to clear links.",
            update,
            "id",
            "version"),
        tool(
            "register_agent",
            "Register an agent profile with an optional runtime label (subject). This does not launch a process.",
            fields(
                "name",
                TEXT,
                "role",
                TEXT,
                "subject",
                TEXT,
                "color",
                Map.of("type", "string", "pattern", "^#[0-9a-fA-F]{6}$")),
            "name",
            "role"),
        tool(
            "delete_item",
            "Permanently delete one work item using its current version. Rejects children, incoming dependencies, and unreleased claims (even expired). Deletes its guide, labels and outgoing dependencies; retains activity and reserves ticket keys. Delete children separately first; no subtree cascade.",
            fields("id", ID, "version", VERSION),
            "id",
            "version"),
        tool(
            "delete_agent",
            "Permanently delete an unused agent profile. Release its claims and clear or change all assignments first. Retains activity; does not stop a runtime process.",
            fields("agentId", UUID),
            "agentId"),
        tool(
            "claim_task",
            "Atomically acquire a 15-minute execution lease on ready or unfinished active work. Use your own registered agentId and the current version. Retain the returned claimToken; only one claimant can win.",
            fields("id", ID, "agentId", UUID, "version", VERSION),
            "id",
            "agentId",
            "version"),
        tool(
            "release_task",
            "Release your current claim using version and claimToken before handoff. Assignment remains. Leave context in a comment, then request reassignment if needed.",
            fields("id", ID, "agentId", UUID, "version", VERSION, "claimToken", UUID),
            "id",
            "agentId",
            "version",
            "claimToken"),
        tool(
            "heartbeat",
            "Renew all unexpired claims for an agent profile. Supply every held task’s itemId and current claimToken in claims. Expired claims must be reacquired; heartbeat does not resurrect them.",
            fields(
                "agentId",
                UUID,
                "claims",
                array(schema(fields("itemId", ID, "claimToken", UUID), "itemId", "claimToken"))),
            "agentId",
            "claims"),
        tool(
            "add_comment",
            "Append progress, a blocker, or handoff context. Activity is recorded under the shared workspace actor.",
            fields("id", ID, "body", TEXT),
            "id",
            "body"),
        tool(
            "list_agents",
            "List registered identities and heartbeat-derived presence. Registry presence is not an independently verified process health check.",
            Map.of()),
        tool(
            "get_activity",
            "Read recent coordination events. Optional itemId and limit up to 200. Full history is retained in the database; workspace snapshots expose recent events.",
            fields(
                "itemId", ID, "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200))));
  }

  private static Map<String, Object> tool(
      String name, String description, Map<String, Object> properties, String... required) {
    Map<String, Object> definition =
        new LinkedHashMap<>(
            Map.of(
                "name",
                name,
                "description",
                description,
                "inputSchema",
                schema(properties, required)));
    if (name.equals("delete_item") || name.equals("delete_agent"))
      definition.put("annotations", Map.of("readOnlyHint", false, "destructiveHint", true));
    return definition;
  }

  private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        List.of(required),
        "additionalProperties",
        false);
  }

  private static Map<String, Object> enumeration(String... values) {
    return Map.of("type", "string", "enum", List.of(values));
  }

  private static Map<String, Object> array(Map<String, Object> items) {
    return Map.of("type", "array", "items", items);
  }

  private static Map<String, Object> fields(Object... pairs) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
    return fields;
  }
}
