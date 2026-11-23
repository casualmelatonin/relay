package com.relay.coordination;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
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
  void rendersTheProjectWorkflowAndTicketPages() throws Exception {
    mvc.perform(get("/"))
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
      mvc.perform(get("/").param("view", view)).andExpect(status().isOk());
    mvc.perform(get("/items/new")).andExpect(status().isOk());
    mvc.perform(get("/agents/new")).andExpect(status().isOk());
  }

  @Test
  void ticketDetailsStayFocusedWhileExistingGuidesRemainAvailableViaApi() throws Exception {
    var created =
        mvc.perform(
                post("/api/v1/items")
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
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn();
    assertEquals(
        "Understand constructor injection.",
        mapper.readTree(saved.getResponse().getContentAsString()).get("learningGoal").asText());
    mvc.perform(
            put("/api/v1/items/" + id + "/guide")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)))
        .andExpect(status().isConflict());
    mvc.perform(get("/items/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("What to build")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Definition of done")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Edit details")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Understand this chunk"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Save learning guide"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("1 Understand"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Work through the guide"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"purpose\""))));
    mvc.perform(get("/api/v1/items/" + id + "/guide"))
        .andExpect(jsonPath("$.learningGoal").value("Understand constructor injection."));
    mvc.perform(post("/ui/items/" + id + "/guide")).andExpect(status().isNotFound());
  }

  @Test
  void chunkPreviewShowsFullEscapedContentAndKeepsEditingAndRecoveryAvailable() throws Exception {
    String description =
        "First line\n\n"
            + "A focused implementation step.\n".repeat(45)
            + "<script>alert(1)</script>";
    var created =
        mvc.perform(
                post("/api/v1/items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of(
                                "title",
                                "Readable chunk " + UUID.randomUUID(),
                                "type",
                                "TASK",
                                "description",
                                description,
                                "acceptance",
                                "Verify the full content.\nReview the outcome."))))
            .andExpect(status().isCreated())
            .andReturn();
    var item = mapper.readTree(created.getResponse().getContentAsString());
    String url = "/items/" + item.path("id").asLong();
    mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(org.springframework.web.util.HtmlUtils.htmlEscape(description))))
        .andExpect(content().string(containsString("id=\"definition-of-done\"")))
        .andExpect(content().string(containsString("No result recorded yet.")))
        .andExpect(content().string(containsString("aria-label=\"On this chunk\"")))
        .andExpect(content().string(not(containsString("name=\"description\""))))
        .andExpect(content().string(not(containsString("<script>"))))
        .andExpect(content().string(containsString(url + "?edit=true")));
    mvc.perform(get(url).param("edit", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"description\"")))
        .andExpect(
            content()
                .string(
                    containsString(org.springframework.web.util.HtmlUtils.htmlEscape(description))))
        .andExpect(content().string(containsString("Save changes")));

    var saved =
        mvc.perform(
                post("/ui" + url + "/update")
                    .param("version", item.path("version").asText())
                    .param("title", item.path("title").asText())
                    .param("description", description)
                    .param("acceptance", "Verified preview")
                    .param("output", "Result and verification evidence"))
            .andExpect(redirectedUrl(url))
            .andReturn();
    mvc.perform(get(url).flashAttrs(saved.getFlashMap()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Result and verification evidence")))
        .andExpect(content().string(not(containsString("name=\"description\""))));

    var rejected =
        mvc.perform(
                post("/ui" + url + "/update")
                    .param("version", item.path("version").asText())
                    .param("title", item.path("title").asText())
                    .param("description", "Preserve this draft after a version conflict"))
            .andExpect(redirectedUrl(url))
            .andReturn();
    mvc.perform(get(url).flashAttrs(rejected.getFlashMap()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("role=\"alert\"")))
        .andExpect(content().string(containsString("name=\"description\"")))
        .andExpect(content().string(containsString("Preserve this draft after a version conflict")))
        .andExpect(content().string(not(containsString("id=\"what-to-build\""))));
  }
}
