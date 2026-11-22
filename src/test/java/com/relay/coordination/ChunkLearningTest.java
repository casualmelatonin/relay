package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkLearningTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @Test
  void rendersTheProjectFirstWorkflowAndChunkGuide() throws Exception {
    mvc.perform(get("/").with(user("operator").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("TEAM WORKSPACE"))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Your projects")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Understand &amp; build")));
    for (String view :
        new String[] {"tasks", "board", "initiatives", "agents", "activity", "tools"})
      mvc.perform(get("/").param("view", view).with(user("operator").roles("ADMIN")))
          .andExpect(status().isOk());
    mvc.perform(get("/items/new").with(user("operator").roles("ADMIN"))).andExpect(status().isOk());
    mvc.perform(get("/agents/new").with(user("operator").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void guidesPersistIndependentlyAndRejectStaleEdits() throws Exception {
    var created =
        mvc.perform(
                post("/api/v1/items")
                    .with(httpBasic("operator", "test-operator-password"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "title", "Focused learning " + UUID.randomUUID(), "type", "TASK"))))
            .andExpect(status().isCreated())
            .andReturn();
    long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
    var body =
        Map.of(
            "version",
            -1,
            "purpose",
            "Build one independently reviewable change.",
            "learningGoal",
            "Understand constructor injection.",
            "steps",
            "Read, explain, implement, verify.",
            "reflection",
            "I can explain why the service owns the transaction.");
    var saved =
        mvc.perform(
                put("/api/v1/items/" + id + "/guide")
                    .with(httpBasic("operator", "test-operator-password"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    assertEquals(
        "Understand constructor injection.",
        mapper.readTree(saved.getResponse().getContentAsString()).get("learningGoal").asText());
    mvc.perform(
            put("/api/v1/items/" + id + "/guide")
                .with(httpBasic("operator", "test-operator-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isConflict());
    mvc.perform(get("/items/" + id).with(user("operator").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "Understand this chunk before you build it")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Understand constructor injection.")));
    mvc.perform(post("/ui/items/" + id + "/guide").with(user("operator").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }
}
