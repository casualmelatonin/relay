package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CoordinationIntegrationTest.ClockConfiguration.class)
class CoordinationIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.set(NOW);
  }

  @Test
  void workspaceRestAndMcpAreAccessibleWithoutCredentials() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/")).andExpect(status().isOk());
    JsonNode workspace = success(get("/api/v1/workspace"), null);
    assertTrue(workspace.path("items").isArray());
    assertTrue(workspace.path("agents").isArray());
    assertTrue(workspace.path("activity").isArray());
    JsonNode tools = mcp("tools/list", payload());
    assertTrue(tools.path("result").path("tools").isArray());
    mvc.perform(get("/login")).andExpect(status().isNotFound());
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
  }

  @Test
  void browserFormsCreateWorkWithoutLoginOrSessionTokens() throws Exception {
    mvc.perform(post("/ui/items").param("title", unique("Browser chunk")).param("type", "TASK"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern(
                "/items/*"));
    mvc.perform(post("/ui/agents").param("name", unique("Browser agent")).param("role", "Builder"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                "/?view=agents"));
  }

  @Test
  void crossOriginMutationIsRejectedBeforeTheController() throws Exception {
    mvc.perform(
            post("/ui/items")
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .param("title", "Cross-origin form")
                .param("type", "TASK"))
        .andExpect(status().isForbidden());
    perform(
            post("/api/v1/items").header(HttpHeaders.ORIGIN, "https://untrusted.example"),
            payload("title", unique("Cross origin"), "type", "TASK"))
        .andExpect(status().isForbidden());
    JsonNode created =
        success(
            post("/api/v1/items").header(HttpHeaders.ORIGIN, "http://localhost"),
            payload("title", unique("Same origin"), "type", "TASK"));
    assertTrue(created.path("id").asLong() > 0);
  }

  @Test
  void sharedWorkspaceCanRegisterAndClaimAnyAgentProfile() throws Exception {
    JsonNode profile = register("runtime-atlas");
    JsonNode task = ready(payload("assigneeId", profile.path("id").asText()));
    JsonNode claimed = claim(task, profile);
    assertEquals(profile.path("id"), claimed.path("claimantId"));
    assertFalse(claimed.path("claimToken").asText().isBlank());
  }

  @Test
  void hierarchyRejectsInvalidLevelsAndTransitiveCycles() throws Exception {
    JsonNode initiative = create(payload("type", "INITIATIVE"));
    JsonNode epic = create(payload("type", "EPIC", "parentId", id(initiative)));
    JsonNode story = create(payload("type", "STORY", "parentId", id(epic)));
    JsonNode task = create(payload("parentId", id(story)));
    assertEquals(id(story), task.path("parentId").asLong());
    perform(
            post("/api/v1/items"),
            payload("title", unique("Invalid story"), "type", "STORY", "parentId", id(initiative)))
        .andExpect(status().is4xxClientError());

    JsonNode parent = create(payload());
    JsonNode child = create(payload("parentId", id(parent)));
    JsonNode grandchild = create(payload("parentId", id(child)));
    assertRejectedUnchanged(parent, payload("parentId", id(parent)));
    assertRejectedUnchanged(parent, payload("parentId", id(grandchild)));
  }

  @Test
  void unfinishedDependenciesGateClaimsAndExecutionUntilTheyComplete() throws Exception {
    JsonNode agent = register("operator");
    JsonNode prerequisite = create(payload());
    JsonNode dependent = ready(payload("dependencies", List.of(id(prerequisite))));

    perform(
            post(itemUrl(dependent) + "/claim"),
            payload("agentId", agent.path("id").asText(), "version", version(dependent)))
        .andExpect(status().is4xxClientError());
    for (String state : List.of("IN_PROGRESS", "REVIEW", "DONE")) {
      assertRejectedUnchanged(dependent, payload("status", state, "output", "Completed result"));
    }
    updateItem(prerequisite, payload("status", "DONE", "output", "Prerequisite delivered"));
    JsonNode claimed = claim(dependent, agent);
    assertEquals("IN_PROGRESS", claimed.path("status").asText());
    assertEquals(agent.path("id"), claimed.path("claimantId"));
  }

  @Test
  void dependencyGraphIncludesParentCompletionAndReparentedDescendants() throws Exception {
    JsonNode left = create(payload());
    JsonNode right = create(payload());
    create(payload("parentId", id(left), "dependencies", List.of(id(right))));
    JsonNode rightChild = create(payload("parentId", id(right)));
    assertRejectedUnchanged(rightChild, payload("dependencies", List.of(id(left))));
    assertRejectedUnchanged(left, payload("dependencies", List.of(id(left))));

    JsonNode newParent = create(payload());
    JsonNode branch = create(payload());
    create(payload("parentId", id(branch), "dependencies", List.of(id(newParent))));
    assertRejectedUnchanged(branch, payload("parentId", id(newParent)));
  }

  @Test
  void completionRequiresEvidenceAndFinishedChildrenAndProtectsDoneAncestors() throws Exception {
    JsonNode parent = create(payload());
    JsonNode child = create(payload("parentId", id(parent)));
    assertRejectedUnchanged(child, payload("status", "DONE", "output", "   "));
    assertRejectedUnchanged(parent, payload("status", "DONE", "output", "Parent complete"));
    child =
        updateItem(child, payload("status", "DONE", "output", "Child artifact and verification"));
    parent = updateItem(parent, payload("status", "DONE", "output", "All children verified"));
    assertRejectedUnchanged(child, payload("status", "READY"));
    perform(
            post("/api/v1/items"),
            payload(
                "title", unique("New unfinished child"), "type", "TASK", "parentId", id(parent)))
        .andExpect(status().is4xxClientError());
    assertEquals("DONE", getItem(parent).path("status").asText());
  }

  @Test
  void staleVersionsRejectLostUpdatesWithoutProducingActivity() throws Exception {
    JsonNode original = create(payload());
    JsonNode updated = updateItem(original, payload("title", "The current title"));
    long activityBefore = itemActivityCount(id(original));
    perform(
            patch(itemUrl(original)),
            payload("version", version(original), "title", "Stale overwrite"))
        .andExpect(status().isConflict());
    JsonNode current = getItem(original);
    assertEquals(updated, current);
    assertEquals(activityBefore, itemActivityCount(id(original)));
  }

  @Test
  void missingVersionsCannotModifyOrClaimANewVersionZeroItem() throws Exception {
    JsonNode task = create(payload());
    assertEquals(0, version(task));
    JsonNode agent = register("operator");
    perform(patch(itemUrl(task)), payload("title", "Missing version"))
        .andExpect(status().isUnprocessableEntity());
    perform(post(itemUrl(task) + "/claim"), payload("agentId", agent.path("id").asText()))
        .andExpect(status().isUnprocessableEntity());
    assertEquals(task, getItem(task));
    JsonNode rejected =
        mcp(
            "tools/call",
            payload(
                "name",
                "update_item",
                "arguments",
                payload("id", id(task), "title", "Missing MCP version")));
    assertTrue(rejected.path("result").path("isError").asBoolean());
    assertEquals(task, getItem(task));
  }

  @Test
  void liveClaimBlocksReassignmentAndRequiresMatchingAgentProofForProgress() throws Exception {
    JsonNode agent = register("agent");
    JsonNode other = register("operator");
    JsonNode task = ready(payload("assigneeId", agent.path("id").asText()));
    JsonNode claimed = claim(task, agent);
    assertFalse(claimed.path("claimToken").asText().isBlank());
    assertRejectedUnchanged(claimed, payload("assigneeId", other.path("id").asText()));

    for (Map<String, Object> proof :
        List.of(
            payload(),
            payload("agentId", agent.path("id").asText()),
            payload(
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                UUID.randomUUID().toString()))) {
      Map<String, Object> body = payload("version", version(claimed), "output", "Unproven result");
      body.putAll(proof);
      perform(patch(itemUrl(claimed)), body).andExpect(status().is4xxClientError());
      assertEquals(claimed, getItem(claimed));
    }
    JsonNode done =
        success(
            patch(itemUrl(claimed)),
            payload(
                "version",
                version(claimed),
                "status",
                "DONE",
                "output",
                "Implemented and verified",
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                claimed.path("claimToken").asText()));
    assertEquals("DONE", done.path("status").asText());
    assertTrue(done.path("claimantId").isNull());
    assertTrue(done.path("claimToken").isNull());
    assertTrue(done.path("leaseUntil").isNull());
  }

  @Test
  void expiredClaimsCannotReportProgressAndReclaimInvalidatesOldRunnerTokens() throws Exception {
    JsonNode agent = register("agent");
    JsonNode task = ready(payload("assigneeId", agent.path("id").asText()));
    JsonNode first = claim(task, agent);
    String oldToken = first.path("claimToken").asText();
    clock.set(Instant.parse(first.path("leaseUntil").asText()));

    perform(
            patch(itemUrl(first)),
            payload(
                "version",
                version(first),
                "output",
                "Expired result",
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                oldToken))
        .andExpect(status().is4xxClientError());
    perform(
            post("/api/v1/agents/" + agent.path("id").asText() + "/heartbeat"),
            payload("claims", List.of(payload("itemId", id(first), "claimToken", oldToken))))
        .andExpect(status().is4xxClientError());
    assertEquals(first.path("leaseUntil"), getItem(first).path("leaseUntil"));

    JsonNode releasedExpired =
        success(
            post(itemUrl(first) + "/release"),
            payload(
                "version",
                version(first),
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                oldToken));
    assertTrue(releasedExpired.path("claimToken").isNull());
    JsonNode current = claim(releasedExpired, agent);
    assertNotEquals(oldToken, current.path("claimToken").asText());
    perform(
            patch(itemUrl(current)),
            payload(
                "version",
                version(current),
                "output",
                "Old runner result",
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                oldToken))
        .andExpect(status().is4xxClientError());
    perform(
            post(itemUrl(current) + "/release"),
            payload(
                "version",
                version(current),
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                oldToken))
        .andExpect(status().is4xxClientError());
    JsonNode released =
        success(
            post(itemUrl(current) + "/release"),
            payload(
                "version",
                version(current),
                "agentId",
                agent.path("id").asText(),
                "claimToken",
                current.path("claimToken").asText()));
    assertEquals("READY", released.path("status").asText());
    assertTrue(released.path("claimToken").isNull());
  }

  @Test
  void invalidHeartbeatProofRollsBackAllLeaseAndPresenceChanges() throws Exception {
    JsonNode agent = register("agent");
    JsonNode first = claim(ready(payload("assigneeId", agent.path("id").asText())), agent);
    JsonNode second = claim(ready(payload("assigneeId", agent.path("id").asText())), agent);
    JsonNode agentBefore = findAgent(agent.path("id").asText());
    clock.set(NOW.plus(Duration.ofMinutes(5)));
    String endpoint = "/api/v1/agents/" + agent.path("id").asText() + "/heartbeat";

    perform(
            post(endpoint),
            payload(
                "claims",
                List.of(
                    payload("itemId", id(first), "claimToken", first.path("claimToken").asText()))))
        .andExpect(status().isConflict());
    assertEquals(first, getItem(first));
    assertEquals(second, getItem(second));
    assertEquals(
        agentBefore.path("lastSeen"), findAgent(agent.path("id").asText()).path("lastSeen"));

    perform(
            post(endpoint),
            payload(
                "claims",
                List.of(
                    payload("itemId", id(first), "claimToken", first.path("claimToken").asText()),
                    payload("itemId", id(second), "claimToken", UUID.randomUUID().toString()))))
        .andExpect(status().is4xxClientError());
    assertEquals(first, getItem(first));
    assertEquals(second, getItem(second));
    assertEquals(
        agentBefore.path("lastSeen"), findAgent(agent.path("id").asText()).path("lastSeen"));

    success(
        post(endpoint),
        payload(
            "claims",
            List.of(
                payload("itemId", id(first), "claimToken", first.path("claimToken").asText()),
                payload("itemId", id(second), "claimToken", second.path("claimToken").asText()))));
    assertEquals(
        NOW.plus(Duration.ofMinutes(20)),
        Instant.parse(getItem(first).path("leaseUntil").asText()));
    assertEquals(
        NOW.plus(Duration.ofMinutes(20)),
        Instant.parse(getItem(second).path("leaseUntil").asText()));
  }

  @Test
  void simultaneousClaimsHaveExactlyOneCommittedWinner() throws Exception {
    JsonNode alice = register("operator");
    JsonNode bob = register("operator");
    JsonNode task = ready(payload());
    CountDownLatch waiting = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (JsonNode agent : List.of(alice, bob)) {
        futures.add(
            executor.submit(
                () -> {
                  waiting.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Claim race did not start");
                  return perform(
                          post(itemUrl(task) + "/claim"),
                          payload("agentId", agent.path("id").asText(), "version", version(task)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      assertTrue(waiting.await(10, TimeUnit.SECONDS));
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) statuses.add(future.get(20, TimeUnit.SECONDS));
      assertEquals(
          1,
          statuses.stream().filter(code -> code >= 200 && code < 300).count(),
          statuses.toString());
      assertEquals(1, statuses.stream().filter(code -> code == 409).count(), statuses.toString());
      JsonNode winner = getItem(task);
      assertTrue(
          Set.of(alice.path("id").asText(), bob.path("id").asText())
              .contains(winner.path("claimantId").asText()));
      assertEquals(version(task) + 1, version(winner));
      assertFalse(winner.path("claimToken").asText().isBlank());
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void commentsUseTheSharedWorkspaceActorInTheAuditTrail() throws Exception {
    JsonNode task = create(payload());
    JsonNode comment =
        success(
            post(itemUrl(task) + "/comments"), payload("body", "Verification artifact is ready"));
    assertEquals("workspace", comment.path("actor").asText());
    assertEquals(id(task), comment.path("itemId").asLong());
    assertEquals("Verification artifact is ready", comment.path("message").asText());
  }

  @Test
  void mcpDiscoveryAndToolCallsUseTheSamePersistentCoordinationRules() throws Exception {
    JsonNode initialized =
        mcp(
            "initialize",
            payload(
                "protocolVersion",
                "2025-03-26",
                "capabilities",
                payload(),
                "clientInfo",
                payload("name", "integration-test", "version", "1.0")));
    assertTrue(initialized.path("result").path("capabilities").has("tools"));
    JsonNode listed = mcp("tools/list", payload());
    Set<String> toolNames = new HashSet<>();
    listed.path("result").path("tools").forEach(tool -> toolNames.add(tool.path("name").asText()));
    assertTrue(
        toolNames.containsAll(
            Set.of(
                "list_work",
                "create_item",
                "update_item",
                "claim_task",
                "heartbeat",
                "get_activity")));

    JsonNode response =
        mcp(
            "tools/call",
            payload(
                "name",
                "create_item",
                "arguments",
                payload("title", unique("MCP task"), "type", "TASK")));
    assertFalse(response.path("result").path("isError").asBoolean());
    JsonNode created =
        mapper.readTree(response.path("result").path("content").get(0).path("text").asText());
    JsonNode persisted = success(get("/api/v1/items/" + created.path("id").asLong()), null);
    assertEquals(created.path("title"), persisted.path("title"));

    JsonNode rejected =
        mcp(
            "tools/call",
            payload(
                "name",
                "update_item",
                "arguments",
                payload("id", id(created), "version", version(created), "status", "DONE")));
    assertTrue(rejected.path("result").path("isError").asBoolean());
    assertEquals("BACKLOG", getItem(created).path("status").asText());
  }

  @Test
  void projectPrefixRenamesExistingDescendantsWithoutChangingRelationships() throws Exception {
    JsonNode project = create(payload("type", "INITIATIVE"));
    JsonNode epic = create(payload("type", "EPIC", "parentId", id(project)));
    JsonNode prerequisite = create(payload("parentId", id(epic)));
    JsonNode task =
        create(payload("parentId", id(epic), "dependencies", List.of(id(prerequisite))));
    JsonNode nested = create(payload("parentId", id(task)));
    JsonNode other = create(payload("type", "INITIATIVE"));
    assertEquals("RLY-" + id(task), task.path("key").asText());

    JsonNode renamed = updateItem(project, payload("keyPrefix", "http"));
    assertEquals("HTTP-" + id(project), renamed.path("key").asText());
    for (JsonNode item : List.of(epic, prerequisite, task, nested)) {
      JsonNode after = getItem(item);
      assertEquals("HTTP-" + id(item), after.path("key").asText());
      assertEquals("HTTP", after.path("keyPrefix").asText());
      assertEquals(item.path("parentId"), after.path("parentId"));
      assertEquals(item.path("dependencies"), after.path("dependencies"));
    }
    assertEquals("RLY-" + id(other), getItem(other).path("key").asText());
    for (JsonNode listed : success(get("/api/v1/items"), null)) {
      if (id(listed) == id(task)) assertEquals("HTTP-" + id(task), listed.path("key").asText());
    }
    String detail =
        mvc.perform(get("/items/" + id(task)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(detail.contains("HTTP-" + id(prerequisite)));
    JsonNode blocked =
        mapper.readTree(
            perform(
                    patch(itemUrl(task)),
                    payload("version", version(task), "status", "IN_PROGRESS"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertTrue(blocked.path("detail").asText().contains("HTTP-" + id(prerequisite)));
    perform(patch(itemUrl(project)), payload("version", version(project), "keyPrefix", "STALE"))
        .andExpect(status().isConflict());
    assertEquals("HTTP", getItem(project).path("keyPrefix").asText());
    JsonNode reset = updateItem(renamed, payload("keyPrefix", ""));
    assertEquals("RLY", reset.path("keyPrefix").asText());
    assertEquals("RLY-" + id(task), getItem(task).path("key").asText());
  }

  @Test
  void prefixInheritanceFollowsNewChildrenAndReparenting() throws Exception {
    JsonNode first = create(payload("type", "INITIATIVE", "keyPrefix", "HTTP"));
    JsonNode second = create(payload("type", "INITIATIVE", "keyPrefix", "NET2"));
    JsonNode epic = create(payload("type", "EPIC", "parentId", id(first)));
    JsonNode task = create(payload("parentId", id(epic)));
    assertEquals("HTTP-" + id(task), task.path("key").asText());
    epic = updateItem(epic, payload("parentId", id(second)));
    assertEquals("NET2-" + id(task), getItem(task).path("key").asText());
    updateItem(epic, payload("clearParent", true));
    assertEquals("RLY-" + id(task), getItem(task).path("key").asText());
    JsonNode preserved = updateItem(first, payload("title", "Changed project title"));
    assertEquals("HTTP", preserved.path("keyPrefix").asText());
  }

  @Test
  void invalidPrefixesAndChildOverridesAreRejectedWithoutMutation() throws Exception {
    JsonNode project = create(payload("type", "INITIATIVE", "keyPrefix", "HTTP"));
    for (String bad : List.of("X", "1HTTP", "HTTP-NET", "TOOLONGPREFIXVALUE", "H T", "éx")) {
      assertRejectedUnchanged(project, payload("keyPrefix", bad));
      perform(
              post("/api/v1/items"),
              payload("title", unique("Invalid"), "type", "INITIATIVE", "keyPrefix", bad))
          .andExpect(status().is4xxClientError());
    }
    JsonNode task = create(payload());
    assertRejectedUnchanged(task, payload("keyPrefix", "OTHER"));
    perform(
            post("/api/v1/items"),
            payload("title", unique("Child prefix"), "type", "TASK", "keyPrefix", "OTHER"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void browserFormsAndMcpSupportProjectPrefixesAndCurrentDependencyKeys() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/ui/items")
                    .param("type", "INITIATIVE")
                    .param("title", unique("Browser prefixed project"))
                    .param("keyPrefix", "web"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    long projectId = Long.parseLong(result.getResponse().getRedirectedUrl().replace("/items/", ""));
    JsonNode project = success(get("/api/v1/items/" + projectId), null);
    assertEquals("WEB-" + projectId, project.path("key").asText());
    String page =
        mvc.perform(get("/items/" + projectId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(page.contains("name=\"keyPrefix\""));
    assertTrue(page.contains("value=\"WEB\""));
    JsonNode epic = create(payload("type", "EPIC", "parentId", projectId));
    JsonNode prerequisite = create(payload("parentId", id(epic)));
    MvcResult childResult =
        mvc.perform(
                post("/ui/items")
                    .param("type", "TASK")
                    .param("title", unique("Dependent browser chunk"))
                    .param("parentId", "" + id(epic))
                    .param("dependencies", prerequisite.path("key").asText()))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    assertNotEquals("/items/new", childResult.getResponse().getRedirectedUrl());
    JsonNode child = success(get("/api/v1" + childResult.getResponse().getRedirectedUrl()), null);
    assertEquals(id(prerequisite), child.path("dependencies").get(0).asLong());
    JsonNode changed =
        mcp(
            "tools/call",
            payload(
                "name",
                "update_item",
                "arguments",
                payload("id", projectId, "version", version(project), "keyPrefix", "HTTP")));
    assertFalse(changed.path("result").path("isError").asBoolean());
    assertEquals("HTTP-" + id(child), getItem(child).path("key").asText());
    JsonNode tools = mcp("tools/list", payload());
    for (JsonNode tool : tools.path("result").path("tools")) {
      if (Set.of("create_item", "update_item").contains(tool.path("name").asText()))
        assertTrue(tool.path("inputSchema").path("properties").has("keyPrefix"));
    }
  }

  private JsonNode create(Map<String, Object> extra) throws Exception {
    Map<String, Object> body = payload("title", unique("Work"), "type", "TASK");
    body.putAll(extra);
    return success(post("/api/v1/items"), body);
  }

  private JsonNode ready(Map<String, Object> extra) throws Exception {
    return updateItem(create(extra), payload("status", "READY"));
  }

  private JsonNode updateItem(JsonNode item, Map<String, Object> fields) throws Exception {
    Map<String, Object> body = payload("version", version(item));
    body.putAll(fields);
    return success(patch(itemUrl(item)), body);
  }

  private JsonNode register(String subject) throws Exception {
    return success(
        post("/api/v1/agents"),
        payload("name", unique("Agent"), "role", "Builder", "subject", subject));
  }

  private JsonNode claim(JsonNode task, JsonNode agent) throws Exception {
    return success(
        post(itemUrl(task) + "/claim"),
        payload("agentId", agent.path("id").asText(), "version", version(task)));
  }

  private JsonNode getItem(JsonNode item) throws Exception {
    return success(get(itemUrl(item)), null);
  }

  private void assertRejectedUnchanged(JsonNode item, Map<String, Object> fields) throws Exception {
    JsonNode before = getItem(item);
    Map<String, Object> body = payload("version", version(before));
    body.putAll(fields);
    perform(patch(itemUrl(item)), body).andExpect(status().is4xxClientError());
    assertEquals(before, getItem(item));
  }

  private long itemActivityCount(long itemId) throws Exception {
    long count = 0;
    for (JsonNode event : success(get("/api/v1/workspace"), null).path("activity")) {
      if (event.path("itemId").asLong(-1) == itemId) count++;
    }
    return count;
  }

  private JsonNode findAgent(String id) throws Exception {
    for (JsonNode agent : success(get("/api/v1/agents"), null)) {
      if (agent.path("id").asText().equals(id)) return agent;
    }
    throw new AssertionError("Registered agent was not listed: " + id);
  }

  private JsonNode mcp(String method, Map<String, Object> params) throws Exception {
    return success(
        post("/mcp"),
        payload(
            "jsonrpc",
            "2.0",
            "id",
            UUID.randomUUID().toString(),
            "method",
            method,
            "params",
            params));
  }

  private JsonNode success(MockHttpServletRequestBuilder request, Map<String, Object> body)
      throws Exception {
    MvcResult result = perform(request, body).andExpect(status().is2xxSuccessful()).andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private ResultActions perform(MockHttpServletRequestBuilder request, Map<String, Object> body)
      throws Exception {
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    return mvc.perform(request);
  }

  private static Map<String, Object> payload(Object... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
    return values;
  }

  private static long id(JsonNode item) {
    return item.path("id").asLong();
  }

  private static long version(JsonNode item) {
    return item.path("version").asLong();
  }

  private static String itemUrl(JsonNode item) {
    return "/api/v1/items/" + id(item);
  }

  private static String unique(String prefix) {
    return prefix + " " + UUID.randomUUID();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current = new AtomicReference<>(NOW);

    void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(current.get(), zone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
