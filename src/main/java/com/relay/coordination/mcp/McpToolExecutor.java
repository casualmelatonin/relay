package com.relay.coordination.mcp;

import com.fasterxml.jackson.databind.*;
import com.relay.coordination.api.WorkspaceController;
import com.relay.coordination.api.dto.*;
import com.relay.coordination.chunk.ChunkGuideService;
import com.relay.coordination.domain.*;
import com.relay.coordination.service.CoordinationService;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Validates tool input and invokes the same application services used by REST and HTML. */
@Component
public final class McpToolExecutor {
  private final CoordinationService coordination;
  private final ObjectMapper mapper;
  private final Validator validator;
  private final Clock clock;
  private final ChunkGuideService guides;

  public McpToolExecutor(
      CoordinationService coordination,
      ObjectMapper mapper,
      Validator validator,
      Clock clock,
      ChunkGuideService guides) {
    this.coordination = coordination;
    this.mapper = mapper;
    this.validator = validator;
    this.clock = clock;
    this.guides = guides;
  }

  public Object execute(String name, JsonNode args, Authentication actor) {
    boolean admin = WorkspaceController.isAdmin(actor);
    String subject = actor.getName();
    return switch (name) {
      case "list_work" -> {
        var workspace = coordination.snapshot();
        yield workspace.items().stream()
            .filter(
                i ->
                    (!args.hasNonNull("type") || i.type().name().equals(args.get("type").asText()))
                        && (!args.hasNonNull("status")
                            || i.status().name().equals(args.get("status").asText()))
                        && (!args.hasNonNull("assigneeId")
                            || String.valueOf(i.assigneeId())
                                .equals(args.get("assigneeId").asText()))
                        && (!args.hasNonNull("parentId")
                            || Long.valueOf(args.get("parentId").asLong()).equals(i.parentId()))
                        && (!args.hasNonNull("query")
                            || (i.key() + " " + i.title() + " " + i.description())
                                .toLowerCase()
                                .contains(args.get("query").asText().toLowerCase()))
                        && (!args.path("availableOnly").asBoolean()
                            || (i.status() == WorkStatus.READY
                                && (i.leaseUntil() == null
                                    || !i.leaseUntil().isAfter(clock.instant()))
                                && i.dependencies().stream()
                                    .allMatch(
                                        d ->
                                            workspace.items().stream()
                                                .anyMatch(
                                                    p ->
                                                        p.id() == d
                                                            && p.status() == WorkStatus.DONE)))))
            .toList();
      }
      case "get_item" -> coordination.getItem(id(args));
      case "get_chunk_guide" -> guides.get(id(args));
      case "update_chunk_guide" ->
          guides.save(
              id(args),
              convert(args, com.relay.coordination.chunk.ChunkGuideRequest.class, "id"),
              subject);
      case "list_agents" -> coordination.snapshot().agents();
      case "get_activity" ->
          coordination.snapshot().activity().stream()
              .filter(
                  e ->
                      !args.hasNonNull("itemId")
                          || Long.valueOf(args.get("itemId").asLong()).equals(e.itemId()))
              .limit(Math.max(1, Math.min(200, args.path("limit").asInt(50))))
              .toList();
      case "create_item" ->
          coordination.create(convert(args, CreateItemRequest.class), subject, admin);
      case "update_item" ->
          coordination.update(
              id(args), convert(args, UpdateItemRequest.class, "id"), subject, admin);
      case "register_agent" ->
          coordination.registerAgent(convert(args, RegisterAgentRequest.class), subject, admin);
      case "claim_task" ->
          coordination.claim(id(args), convert(args, ClaimRequest.class, "id"), subject, admin);
      case "release_task" ->
          coordination.release(id(args), convert(args, ReleaseRequest.class, "id"), subject, admin);
      case "heartbeat" ->
          coordination.heartbeat(
              UUID.fromString(args.path("agentId").asText()),
              convert(args, HeartbeatRequest.class, "agentId"),
              subject,
              admin);
      case "add_comment" ->
          coordination.comment(id(args), convert(args, CommentRequest.class, "id"), subject);
      default -> throw new DomainException(404, "unknown_tool", "Unknown tool.");
    };
  }

  private long id(JsonNode args) {
    if (!args.path("id").canConvertToLong() || args.get("id").asLong() < 1)
      throw new IllegalArgumentException("Invalid id");
    return args.get("id").asLong();
  }

  private <T> T convert(JsonNode args, Class<T> type, String... remove) {
    var fields = args.deepCopy();
    if (fields instanceof com.fasterxml.jackson.databind.node.ObjectNode object)
      Arrays.stream(remove).forEach(object::remove);
    T value = mapper.convertValue(fields, type);
    var violations = validator.validate(value);
    if (!violations.isEmpty())
      throw new DomainException(
          422,
          "validation_failed",
          violations.iterator().next().getPropertyPath()
              + " "
              + violations.iterator().next().getMessage());
    return value;
  }
}
