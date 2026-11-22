package com.relay.coordination.api;

import com.relay.coordination.api.dto.*;
import com.relay.coordination.service.CoordinationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
  public WorkItemDto create(@Valid @RequestBody CreateItemRequest request) {
    return coordination.create(request, "workspace");
  }

  @PatchMapping("/items/{id}")
  public WorkItemDto update(@PathVariable long id, @Valid @RequestBody UpdateItemRequest request) {
    return coordination.update(id, request, "workspace");
  }

  @PostMapping("/items/{id}/claim")
  public WorkItemDto claim(@PathVariable long id, @Valid @RequestBody ClaimRequest request) {
    return coordination.claim(id, request, "workspace");
  }

  @PostMapping("/items/{id}/release")
  public WorkItemDto release(@PathVariable long id, @Valid @RequestBody ReleaseRequest request) {
    return coordination.release(id, request, "workspace");
  }

  @PostMapping("/items/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public ActivityDto comment(@PathVariable long id, @Valid @RequestBody CommentRequest request) {
    return coordination.comment(id, request, "workspace");
  }

  @GetMapping("/agents")
  public List<AgentDto> agents() {
    return coordination.snapshot().agents();
  }

  @PostMapping("/agents")
  @ResponseStatus(HttpStatus.CREATED)
  public AgentDto register(@Valid @RequestBody RegisterAgentRequest request) {
    return coordination.registerAgent(request, "workspace");
  }

  @PostMapping("/agents/{id}/heartbeat")
  public AgentDto heartbeat(@PathVariable UUID id, @Valid @RequestBody HeartbeatRequest request) {
    return coordination.heartbeat(id, request, "workspace");
  }
}
