package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
  void healthIsPublicButRestAndMcpRequireAuthentication() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/workspace")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        payload("jsonrpc", "2.0", "id", 1, "method", "tools/list"))))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/workspace").with(httpBasic("operator", "wrong-password")))
        .andExpect(status().isUnauthorized());
    JsonNode workspace = success(get("/api/v1/workspace"), "operator", null);
    assertTrue(workspace.path("items").isArray());
    assertTrue(workspace.path("agents").isArray());
    assertTrue(workspace.path("activity").isArray());
  }

  @Test
  void browserFormLoginRequiresCsrfWhileAuthenticatedRestDoesNot() throws Exception {
    mvc.perform(
            post("/login")
                .param("username", "operator")
                .param("password", "test-operator-password"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/login")
                .with(csrf())
                .param("username", "operator")
                .param("password", "test-operator-password"))
        .andExpect(status().is3xxRedirection());
    JsonNode created = create(payload());
    assertTrue(created.path("id").asLong() > 0);
  }

  @Test
  void crossOriginMutationIsRejectedBeforeTheController() throws Exception {
    perform(
            post("/api/v1/items").header(HttpHeaders.ORIGIN, "https://untrusted.example"),
            "operator",
            payload("title", unique("Cross origin"), "type", "TASK"))
        .andExpect(status().isForbidden());
    JsonNode created =
        success(
            post("/api/v1/items").header(HttpHeaders.ORIGIN, "http://localhost"),
            "operator",
            payload("title", unique("Same origin"), "type", "TASK"));
    assertTrue(created.path("id").asLong() > 0);
  }

  @Test
  void agentIdentityCannotRegisterOrExecuteAsAnotherPrincipal() throws Exception {
    JsonNode owned =
        success(
            post("/api/v1/agents"),
            "agent",
            payload("name", unique("Self"), "role", "Builder", "subject", "agent"));
    assertEquals("agent", owned.path("subject").asText());
    perform(
            post("/api/v1/agents"),
            "agent",
            payload("name", unique("Impersonation"), "role", "Builder", "subject", "operator"))
        .andExpect(status().isForbidden());

    JsonNode foreign = register("operator");
    JsonNode task = ready(payload("assigneeId", foreign.path("id").asText()));
    perform(
            post(itemUrl(task) + "/claim"),
            "agent",
            payload("agentId", foreign.path("id").asText(), "version", version(task)))
        .andExpect(status().isForbidden());
    assertTrue(getItem(task).path("claimantId").isNull());
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
            "operator",
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
            "operator",
            payload("agentId", agent.path("id").asText(), "version", version(dependent)))
        .andExpect(status().is4xxClientError());
    for (String state : List.of("IN_PROGRESS", "REVIEW", "DONE")) {
      assertRejectedUnchanged(dependent, payload("status", state, "output", "Completed result"));
    }
    updateItem(prerequisite, payload("status", "DONE", "output", "Prerequisite delivered"));
    JsonNode claimed = claim(dependent, agent, "operator");
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
            "operator",
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
            "operator",
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
    perform(patch(itemUrl(task)), "operator", payload("title", "Missing version"))
        .andExpect(status().isUnprocessableEntity());
    perform(
            post(itemUrl(task) + "/claim"),
            "operator",
            payload("agentId", agent.path("id").asText()))
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
    JsonNode claimed = claim(task, agent, "agent");
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
      perform(patch(itemUrl(claimed)), "agent", body).andExpect(status().is4xxClientError());
      assertEquals(claimed, getItem(claimed));
    }
    JsonNode done =
        success(
            patch(itemUrl(claimed)),
            "agent",
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
    JsonNode first = claim(task, agent, "agent");
    String oldToken = first.path("claimToken").asText();
    clock.set(Instant.parse(first.path("leaseUntil").asText()));

    perform(
            patch(itemUrl(first)),
            "agent",
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
            "agent",
            payload("claims", List.of(payload("itemId", id(first), "claimToken", oldToken))))
        .andExpect(status().is4xxClientError());
    assertEquals(first.path("leaseUntil"), getItem(first).path("leaseUntil"));

    JsonNode current = claim(getItem(first), agent, "agent");
    assertNotEquals(oldToken, current.path("claimToken").asText());
    perform(
            patch(itemUrl(current)),
            "agent",
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
            "agent",
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
            "agent",
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
    JsonNode first = claim(ready(payload("assigneeId", agent.path("id").asText())), agent, "agent");
    JsonNode second =
        claim(ready(payload("assigneeId", agent.path("id").asText())), agent, "agent");
    JsonNode agentBefore = findAgent(agent.path("id").asText());
    clock.set(NOW.plus(Duration.ofMinutes(5)));
    String endpoint = "/api/v1/agents/" + agent.path("id").asText() + "/heartbeat";

    perform(
            post(endpoint),
            "agent",
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
            "agent",
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
        "agent",
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
                          "operator",
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
  void commentsUseTheAuthenticatedPrincipalInTheAuditTrail() throws Exception {
    JsonNode task = create(payload());
    JsonNode comment =
        success(
            post(itemUrl(task) + "/comments"),
            "agent",
            payload("body", "Verification artifact is ready"));
    assertEquals("agent", comment.path("actor").asText());
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
    JsonNode persisted =
        success(get("/api/v1/items/" + created.path("id").asLong()), "operator", null);
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

  private JsonNode create(Map<String, Object> extra) throws Exception {
    Map<String, Object> body = payload("title", unique("Work"), "type", "TASK");
    body.putAll(extra);
    return success(post("/api/v1/items"), "operator", body);
  }

  private JsonNode ready(Map<String, Object> extra) throws Exception {
    return updateItem(create(extra), payload("status", "READY"));
  }

  private JsonNode updateItem(JsonNode item, Map<String, Object> fields) throws Exception {
    Map<String, Object> body = payload("version", version(item));
    body.putAll(fields);
    return success(patch(itemUrl(item)), "operator", body);
  }

  private JsonNode register(String subject) throws Exception {
    return success(
        post("/api/v1/agents"),
        "operator",
        payload("name", unique("Agent"), "role", "Builder", "subject", subject));
  }

  private JsonNode claim(JsonNode task, JsonNode agent, String principal) throws Exception {
    return success(
        post(itemUrl(task) + "/claim"),
        principal,
        payload("agentId", agent.path("id").asText(), "version", version(task)));
  }

  private JsonNode getItem(JsonNode item) throws Exception {
    return success(get(itemUrl(item)), "operator", null);
  }

  private void assertRejectedUnchanged(JsonNode item, Map<String, Object> fields) throws Exception {
    JsonNode before = getItem(item);
    Map<String, Object> body = payload("version", version(before));
    body.putAll(fields);
    perform(patch(itemUrl(item)), "operator", body).andExpect(status().is4xxClientError());
    assertEquals(before, getItem(item));
  }

  private long itemActivityCount(long itemId) throws Exception {
    long count = 0;
    for (JsonNode event : success(get("/api/v1/workspace"), "operator", null).path("activity")) {
      if (event.path("itemId").asLong(-1) == itemId) count++;
    }
    return count;
  }

  private JsonNode findAgent(String id) throws Exception {
    for (JsonNode agent : success(get("/api/v1/agents"), "operator", null)) {
      if (agent.path("id").asText().equals(id)) return agent;
    }
    throw new AssertionError("Registered agent was not listed: " + id);
  }

  private JsonNode mcp(String method, Map<String, Object> params) throws Exception {
    return success(
        post("/mcp"),
        "operator",
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

  private JsonNode success(
      MockHttpServletRequestBuilder request, String principal, Map<String, Object> body)
      throws Exception {
    MvcResult result =
        perform(request, principal, body).andExpect(status().is2xxSuccessful()).andReturn();
    return mapper.readTree(result.getResponse().getContentAsString());
  }

  private ResultActions perform(
      MockHttpServletRequestBuilder request, String principal, Map<String, Object> body)
      throws Exception {
    String password =
        principal.equals("operator") ? "test-operator-password" : "test-agent-password";
    request.with(httpBasic(principal, password));
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
