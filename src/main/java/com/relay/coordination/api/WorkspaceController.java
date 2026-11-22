package com.relay.coordination.api;

import com.relay.coordination.api.dto.*;
import com.relay.coordination.service.CoordinationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Versioned HTTP interface; the same application service also serves the UI and MCP. */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
  private final CoordinationService coordination;

  public WorkspaceController(CoordinationService coordination) {
    this.coordination = coordination;
  }

  @GetMapping("/workspace")
  public WorkspaceSnapshot workspace() {
    return coordination.snapshot();
  }

  @GetMapping("/items")
  public List<WorkItemDto> items() {
    return coordination.snapshot().items();
  }

  @GetMapping("/items/{id}")
  public WorkItemDto item(@PathVariable long id) {
    return coordination.getItem(id);
  }

  @PostMapping("/items")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkItemDto create(@Valid @RequestBody CreateItemRequest request, Authentication actor) {
    return coordination.create(request, actor.getName(), isAdmin(actor));
  }

  @PatchMapping("/items/{id}")
  public WorkItemDto update(
      @PathVariable long id, @Valid @RequestBody UpdateItemRequest request, Authentication actor) {
    return coordination.update(id, request, actor.getName(), isAdmin(actor));
  }

  @PostMapping("/items/{id}/claim")
  public WorkItemDto claim(
      @PathVariable long id, @Valid @RequestBody ClaimRequest request, Authentication actor) {
    return coordination.claim(id, request, actor.getName(), isAdmin(actor));
  }

  @PostMapping("/items/{id}/release")
  public WorkItemDto release(
      @PathVariable long id, @Valid @RequestBody ReleaseRequest request, Authentication actor) {
    return coordination.release(id, request, actor.getName(), isAdmin(actor));
  }

  @PostMapping("/items/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public ActivityDto comment(
      @PathVariable long id, @Valid @RequestBody CommentRequest request, Authentication actor) {
    return coordination.comment(id, request, actor.getName());
  }

  @GetMapping("/agents")
  public List<AgentDto> agents() {
    return coordination.snapshot().agents();
  }

  @PostMapping("/agents")
  @ResponseStatus(HttpStatus.CREATED)
  public AgentDto register(@Valid @RequestBody RegisterAgentRequest request, Authentication actor) {
    return coordination.registerAgent(request, actor.getName(), isAdmin(actor));
  }

  @PostMapping("/agents/{id}/heartbeat")
  public AgentDto heartbeat(
      @PathVariable UUID id, @Valid @RequestBody HeartbeatRequest request, Authentication actor) {
    return coordination.heartbeat(id, request, actor.getName(), isAdmin(actor));
  }

  public static boolean isAdmin(Authentication actor) {
    return actor.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
  }
}
