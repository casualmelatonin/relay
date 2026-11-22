package com.relay.coordination.config;

import com.relay.coordination.api.dto.CreateItemRequest;
import com.relay.coordination.api.dto.RegisterAgentRequest;
import com.relay.coordination.api.dto.UpdateItemRequest;
import com.relay.coordination.api.dto.WorkItemDto;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import com.relay.coordination.service.CoordinationService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Example data belongs in the development lifecycle, never in schema migrations or production. */
@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "relay.demo-data", havingValue = "true")
public class DemoDataConfiguration {
  private static final String ACTOR = "demo-seeder";

  @Bean
  CommandLineRunner seedExamples(
      CoordinationService service, com.relay.coordination.chunk.ChunkGuideService guides) {
    return args -> {
      if (!service.snapshot().items().isEmpty()) return;
      var atlas =
          service.registerAgent(
              new RegisterAgentRequest(
                  "Atlas", "Example profile · Planning", "example-atlas", "#7970ca"),
              ACTOR);
      var nova =
          service.registerAgent(
              new RegisterAgentRequest(
                  "Nova", "Example profile · Frontend", "example-nova", "#d07c58"),
              ACTOR);
      var relay =
          service.registerAgent(
              new RegisterAgentRequest(
                  "Relay", "Example profile · Backend", "example-relay", "#548c8b"),
              ACTOR);
      var sentinel =
          service.registerAgent(
              new RegisterAgentRequest(
                  "Sentinel", "Example profile · Quality", "example-sentinel", "#5c83b5"),
              ACTOR);
      var initiative =
          create(
              service,
              "Build an agent coordination workspace",
              ItemType.INITIATIVE,
              null,
              atlas.id(),
              "Develop a shared planning tool in small, understandable chunks: define the model, build the screens, then add reliable agent execution.",
              "Planning");
      var planning =
          create(
              service,
              "Planning foundation",
              ItemType.EPIC,
              initiative.id(),
              atlas.id(),
              "A shared hierarchy from outcomes to executable work.",
              "Planning");
      var execution =
          create(
              service,
              "Reliable agent execution",
              ItemType.EPIC,
              initiative.id(),
              relay.id(),
              "Safe claims and useful handoffs between agents.",
              "Core");
      var visibility =
          create(
              service,
              "Shared visibility",
              ItemType.EPIC,
              initiative.id(),
              nova.id(),
              "Understand workload, progress, and blockers.",
              "Workspace");
      move(
          service,
          create(
              service,
              "Define the initiative and epic hierarchy",
              ItemType.STORY,
              planning.id(),
              atlas.id(),
              "Document the planning hierarchy and acceptance criteria.",
              "Planning"),
          WorkStatus.DONE);
      move(
          service,
          create(
              service,
              "Build issue creation and details",
              ItemType.STORY,
              planning.id(),
              nova.id(),
              "Make ownership, context, and acceptance criteria easy to edit.",
              "Frontend"),
          WorkStatus.IN_PROGRESS);
      move(
          service,
          create(
              service,
              "Add acceptance criteria to stories",
              ItemType.TASK,
              planning.id(),
              atlas.id(),
              "Give every piece of work a clear definition of done.",
              "Planning"),
          WorkStatus.READY);
      var claims =
          create(
              service,
              "Implement atomic task claims",
              ItemType.STORY,
              execution.id(),
              relay.id(),
              "Ensure simultaneous requests yield exactly one claimant.",
              "Backend");
      move(service, claims, WorkStatus.REVIEW);
      move(
          service,
          create(
              service,
              "Recover expired execution leases",
              ItemType.TASK,
              execution.id(),
              relay.id(),
              "Reacquire unfinished work without reviving an old token.",
              "Backend"),
          WorkStatus.READY);
      var blocked =
          create(
              service,
              "Verify conflicting task claims",
              ItemType.TASK,
              execution.id(),
              sentinel.id(),
              "Confirm that another agent cannot overwrite a live claim.",
              "Quality");
      service.update(
          blocked.id(),
          new UpdateItemRequest(
              blocked.version(),
              null,
              null,
              false,
              WorkStatus.BLOCKED,
              Priority.HIGH,
              null,
              false,
              null,
              null,
              null,
              null,
              List.of(claims.id()),
              null,
              null,
              null),
          ACTOR);
      move(
          service,
          create(
              service,
              "Document the handoff playbook",
              ItemType.TASK,
              execution.id(),
              atlas.id(),
              "Release a claim, leave context, and assign the next owner.",
              "Docs"),
          WorkStatus.READY);
      move(
          service,
          create(
              service,
              "Build the agent workload overview",
              ItemType.STORY,
              visibility.id(),
              nova.id(),
              "Show assignments, execution claims, and recent presence.",
              "Frontend"),
          WorkStatus.IN_PROGRESS);
      move(
          service,
          create(
              service,
              "Record coordination events",
              ItemType.TASK,
              visibility.id(),
              relay.id(),
              "Commit an audit event in the same transaction as each change.",
              "Backend"),
          WorkStatus.REVIEW);
      move(
          service,
          create(
              service,
              "Design workspace navigation",
              ItemType.TASK,
              visibility.id(),
              nova.id(),
              "Make board, hierarchy, and agent views easy to find.",
              "Design"),
          WorkStatus.DONE);
      create(
          service,
          "Review keyboard and mobile workflows",
          ItemType.TASK,
          visibility.id(),
          sentinel.id(),
          "Verify that native forms work with keyboard and mobile layouts.",
          "Quality");
      for (var item : service.snapshot().items()) {
        if (item.type() != ItemType.STORY && item.type() != ItemType.TASK) continue;
        boolean frontend = item.labels().contains("Frontend");
        boolean backend = item.labels().contains("Backend");
        String learning =
            frontend
                ? "Understand how a server-rendered form flows through a Java controller, a validated DTO, and the application service. Explain why the POST/redirect/GET pattern prevents accidental resubmission."
                : backend
                    ? "Understand the transaction boundary, optimistic version, and execution claim token. Explain which failure each safeguard prevents before changing the code."
                    : "Explain this chunk’s purpose, its boundary in the project, and the check that proves it is complete.";
        String steps =
            frontend
                ? "1. Read WorkspacePageController and WorkItemActionController with your AI assistant. Trace one form submission.\n2. Implement only the screen or interaction named in this chunk.\n3. Check required fields and error recovery.\n4. Use the browser to verify the flow and explain each changed file."
                : backend
                    ? "1. Read CoordinationService and its repository calls. Ask your assistant to explain the invariants.\n2. Make one focused change for this chunk without changing unrelated behavior.\n3. Run the relevant integration test, including the failure path.\n4. Review the diff and record why the approach works."
                    : "1. Read the chunk context and its parent milestone.\n2. Ask your assistant to explain the smallest useful approach.\n3. Deliver that focused change and verify the definition of done.\n4. Review the result together and record what the next chunk needs to know.";
        guides.save(
            item.id(),
            new com.relay.coordination.chunk.ChunkGuideRequest(
                -1L,
                "This chunk delivers one reviewable part of the project: " + item.description(),
                learning,
                steps,
                item.status() == WorkStatus.DONE
                    ? "Example review: the focused change and its verification have been reviewed. Replace this with what you learned in your own project."
                    : ""),
            ACTOR);
      }
    };
  }

  private WorkItemDto create(
      CoordinationService service,
      String title,
      ItemType type,
      Long parent,
      UUID assignee,
      String description,
      String label) {
    return service.create(
        new CreateItemRequest(
            title,
            type,
            parent,
            Priority.MEDIUM,
            assignee,
            description,
            "Implemented behavior is reviewed and a completion summary is attached.",
            List.of(label),
            List.of(),
            null),
        ACTOR);
  }

  private void move(CoordinationService service, WorkItemDto item, WorkStatus status) {
    service.update(
        item.id(),
        new UpdateItemRequest(
            item.version(),
            null,
            null,
            false,
            status,
            null,
            null,
            false,
            null,
            null,
            status == WorkStatus.DONE
                ? "Example completion summary: the work has been reviewed and accepted in this sample project."
                : null,
            null,
            null,
            null,
            null,
            null),
        ACTOR);
  }
}
