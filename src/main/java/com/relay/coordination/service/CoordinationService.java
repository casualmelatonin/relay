package com.relay.coordination.service;

import com.relay.coordination.api.dto.ActivityDto;
import com.relay.coordination.api.dto.AgentDto;
import com.relay.coordination.api.dto.ClaimProof;
import com.relay.coordination.api.dto.ClaimRequest;
import com.relay.coordination.api.dto.CommentRequest;
import com.relay.coordination.api.dto.CreateItemRequest;
import com.relay.coordination.api.dto.HeartbeatRequest;
import com.relay.coordination.api.dto.RegisterAgentRequest;
import com.relay.coordination.api.dto.ReleaseRequest;
import com.relay.coordination.api.dto.UpdateItemRequest;
import com.relay.coordination.api.dto.WorkItemDto;
import com.relay.coordination.api.dto.WorkspaceSnapshot;
import com.relay.coordination.domain.DomainException;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.Priority;
import com.relay.coordination.domain.WorkStatus;
import com.relay.coordination.persistence.ActivityEvent;
import com.relay.coordination.persistence.ActivityEventRepository;
import com.relay.coordination.persistence.AgentProfile;
import com.relay.coordination.persistence.AgentProfileRepository;
import com.relay.coordination.persistence.WorkItem;
import com.relay.coordination.persistence.WorkItemRepository;
import com.relay.coordination.persistence.WorkspaceLockRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary for the shared workspace. The singleton database lock serializes mutations
 * across server replicas, so graph checks, claim acquisition, and audit insertion commit together.
 * The deliberately bounded workspace can later be partitioned into independent workspace locks.
 */
@Service
@Transactional
public class CoordinationService {
  private static final Duration LEASE = Duration.ofMinutes(15);
  private static final int MAX_ITEMS = 2000;
  private static final int MAX_AGENTS = 500;
  private static final int MAX_AGENT_CLAIMS = 100;
  private final WorkItemRepository items;
  private final AgentProfileRepository agents;
  private final ActivityEventRepository activity;
  private final WorkspaceLockRepository locks;
  private final Clock clock;
  private final CompletionGraphPolicy completionPolicy;

  public CoordinationService(
      WorkItemRepository items,
      AgentProfileRepository agents,
      ActivityEventRepository activity,
      WorkspaceLockRepository locks,
      Clock clock,
      CompletionGraphPolicy completionPolicy) {
    this.items = items;
    this.agents = agents;
    this.activity = activity;
    this.locks = locks;
    this.clock = clock;
    this.completionPolicy = completionPolicy;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public WorkspaceSnapshot snapshot() {
    List<WorkItem> work = items.findAllByOrderByIdAsc();
    Instant now = clock.instant();
    Map<Long, WorkItem> indexed = index(work);
    return new WorkspaceSnapshot(
        work.stream().map(item -> dto(item, indexed::get)).toList(),
        agents.findAllByOrderByNameAsc().stream().map(a -> dto(a, work, now)).toList(),
        activity.findTop200ByOrderByCreatedAtDescIdDesc().stream().map(this::dto).toList());
  }

  @Transactional(readOnly = true)
  public WorkItemDto getItem(long id) {
    return dto(items.findById(id).orElseThrow(() -> missing("Work item not found.")));
  }

  public AgentDto registerAgent(RegisterAgentRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(request != null, "A registration request is required.");
    String name = text(request.name(), "Agent name", 80, true);
    String role = text(request.role(), "Role", 160, true);
    String subject =
        request.subject() == null ? actor : text(request.subject(), "Subject", 200, true);
    if (agents.count() >= MAX_AGENTS)
      throw invalid("agent_limit", "This workspace supports up to 500 agent profiles.");
    if (agents.existsByNormalizedName(name.toLowerCase(Locale.ROOT))) {
      throw conflict("agent_name_exists", "An agent with that name already exists.");
    }
    String color = request.color() == null ? "#7270c5" : request.color();
    require(color.matches("^#[0-9a-fA-F]{6}$"), "Color must be a six-digit hexadecimal color.");
    Instant now = clock.instant();
    AgentProfile profile = agents.saveAndFlush(new AgentProfile(name, role, subject, color, now));
    record(null, actor, "Registered agent " + name + " (" + role + ").", now);
    return dto(profile, List.of(), now);
  }

  public WorkItemDto create(CreateItemRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(request != null, "A creation request is required.");
    require(request.type() != null, "A work item type is required.");
    String title = text(request.title(), "Title", 240, true);
    Map<Long, WorkItem> graph = graph();
    if (graph.size() >= MAX_ITEMS)
      throw invalid("item_limit", "This workspace supports up to 2,000 work items.");
    if (request.assigneeId() != null) agent(request.assigneeId());
    Set<Long> dependencies = dependencies(request.dependencies());
    dependencies.forEach(id -> item(graph, id));
    WorkItem created =
        new WorkItem(
            title,
            request.type(),
            positiveOrNull(request.parentId()),
            request.priority() == null ? Priority.MEDIUM : request.priority(),
            request.assigneeId(),
            text(orEmpty(request.description()), "Description", 8000, false),
            text(orEmpty(request.acceptance()), "Acceptance criteria", 8000, false),
            labels(request.labels()),
            dependencies,
            clock.instant());
    require(
        request.keyPrefix() == null
            || request.keyPrefix().isBlank()
            || request.type() == ItemType.INITIATIVE,
        "Ticket prefixes can only be set on projects; child work inherits its project prefix.");
    if (request.type() == ItemType.INITIATIVE)
      created.setKeyPrefix(WorkItemKeys.normalize(request.keyPrefix()));
    completionPolicy.validateParent(created, graph);
    items.saveAndFlush(created);
    graph.put(created.getId(), created);
    completionPolicy.validateGraph(graph);
    record(
        created.getId(),
        actor,
        "Created " + created.getType() + " “" + title + "”.",
        created.getCreatedAt());
    return dto(created);
  }

  public WorkItemDto update(long id, UpdateItemRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(request != null, "An update request is required.");
    Map<Long, WorkItem> graph = graph();
    WorkItem current = item(graph, id);
    checkVersion(current, request.version());
    require(
        !(request.clearParent() && request.parentId() != null),
        "Specify parentId or clearParent, not both.");
    require(
        !(request.clearAssignee() && request.assigneeId() != null),
        "Specify assigneeId or clearAssignee, not both.");
    Instant now = clock.instant();
    UUID nextAssignee =
        request.clearAssignee()
            ? null
            : request.assigneeId() == null ? current.getAssigneeId() : request.assigneeId();
    boolean assignmentChanged = !Objects.equals(nextAssignee, current.getAssigneeId());
    if (assignmentChanged) {
      if (current.hasActiveClaim(now))
        throw conflict("active_claim", "Release the active claim before reassigning work.");
      if (nextAssignee != null) agent(nextAssignee);
    }
    if (current.getClaimToken() != null
        || request.agentId() != null
        || request.claimToken() != null) {
      validateProof(current, request.agentId(), request.claimToken(), now, true);
    }
    // Resolve and validate text before touching managed entities: invalid requests roll back
    // cleanly.
    String title = request.title() == null ? null : text(request.title(), "Title", 240, true);
    String description =
        request.description() == null
            ? null
            : text(request.description(), "Description", 8000, false);
    String acceptance =
        request.acceptance() == null
            ? null
            : text(request.acceptance(), "Acceptance criteria", 8000, false);
    String output = request.output() == null ? null : text(request.output(), "Output", 8000, false);
    Set<String> nextLabels = request.labels() == null ? null : labels(request.labels());
    Set<Long> nextDependencies =
        request.dependencies() == null ? null : dependencies(request.dependencies());
    if (nextDependencies != null) nextDependencies.forEach(dependency -> item(graph, dependency));
    if (request.parentId() != null) positiveOrNull(request.parentId());

    if (request.keyPrefix() != null) {
      require(
          current.getType() == ItemType.INITIATIVE,
          "Ticket prefixes can only be set on projects; child work inherits its project prefix.");
      current.setKeyPrefix(WorkItemKeys.normalize(request.keyPrefix()));
    }
    if (title != null) current.setTitle(title);
    if (description != null) current.setDescription(description);
    if (acceptance != null) current.setAcceptance(acceptance);
    if (output != null) current.setOutput(output);
    if (request.clearParent() || request.parentId() != null)
      current.setParentId(request.clearParent() ? null : request.parentId());
    if (request.status() != null) current.setStatus(request.status());
    if (request.priority() != null) current.setPriority(request.priority());
    if (nextLabels != null) current.replaceLabels(nextLabels);
    if (nextDependencies != null) current.replaceDependencies(nextDependencies);
    if (assignmentChanged) {
      current.setAssigneeId(nextAssignee);
      current.clearClaim();
    }
    completionPolicy.validateGraph(graph);
    if (current.getStatus() == WorkStatus.DONE) current.clearClaim();
    current.touch(now);
    items.flush();
    record(id, actor, "Updated work item; status " + current.getStatus() + ".", now);
    return dto(current);
  }

  public WorkItemDto claim(long id, ClaimRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(
        request != null && request.agentId() != null, "An agent and current version are required.");
    Map<Long, WorkItem> graph = graph();
    WorkItem current = item(graph, id);
    checkVersion(current, request.version());
    AgentProfile profile = agent(request.agentId());
    Instant now = clock.instant();
    require(executable(current), "Only a story or task can hold an execution claim.");
    if (current.getStatus() != WorkStatus.READY && current.getStatus() != WorkStatus.IN_PROGRESS) {
      throw conflict("not_ready", "Move this work to Ready before claiming it.");
    }
    if (current.hasActiveClaim(now))
      throw conflict(
          "already_claimed", "This work already has an active claim. Use heartbeat to renew it.");
    if (current.getAssigneeId() != null && !current.getAssigneeId().equals(profile.getId())) {
      throw conflict(
          "assigned_elsewhere",
          "This work is assigned to another agent. Reassign it before claiming.");
    }
    completionPolicy.requireDependenciesDone(current, graph);
    long active =
        graph.values().stream()
            .filter(i -> profile.getId().equals(i.getClaimantId()) && i.hasActiveClaim(now))
            .count();
    if (active >= MAX_AGENT_CLAIMS)
      throw invalid("claim_limit", "An agent may hold at most 100 active claims.");
    current.claim(profile.getId(), UUID.randomUUID(), now.plus(LEASE));
    current.touch(now);
    profile.seenAt(now);
    items.flush();
    record(id, actor, profile.getName() + " claimed work for 15 minutes.", now);
    return dto(current);
  }

  public WorkItemDto release(long id, ReleaseRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(
        request != null && request.agentId() != null, "An agent and current version are required.");
    WorkItem current = items.findById(id).orElseThrow(() -> missing("Work item not found."));
    checkVersion(current, request.version());
    AgentProfile profile = agent(request.agentId());
    if (!profile.getId().equals(current.getClaimantId()))
      throw conflict("claim_mismatch", "This agent does not hold the claim.");
    Instant now = clock.instant();
    validateProof(current, profile.getId(), request.claimToken(), now, false);
    current.clearClaim();
    if (current.getStatus() == WorkStatus.IN_PROGRESS) current.setStatus(WorkStatus.READY);
    current.touch(now);
    items.flush();
    record(id, actor, profile.getName() + " released the execution claim.", now);
    return dto(current);
  }

  public AgentDto heartbeat(UUID agentId, HeartbeatRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(
        request != null && request.claims() != null && request.claims().size() <= MAX_AGENT_CLAIMS,
        "Supply up to 100 claim proofs, or an empty claims list for presence only.");
    AgentProfile profile = agent(agentId);
    Map<Long, WorkItem> graph = graph();
    Instant now = clock.instant();
    Set<Long> seen = new HashSet<>();
    List<WorkItem> renew = new ArrayList<>();
    for (ClaimProof proof : request.claims()) {
      require(
          proof != null && proof.claimToken() != null && proof.itemId() > 0,
          "Every claim proof needs an item ID and claim token.");
      require(seen.add(proof.itemId()), "Do not repeat an item in a heartbeat.");
      WorkItem current = item(graph, proof.itemId());
      // Tokens identify a current execution lease: never revive an expired worker.
      if (!profile.getId().equals(current.getClaimantId())
          || !proof.claimToken().equals(current.getClaimToken())
          || !current.hasActiveClaim(now)) {
        throw conflict(
            "stale_claim", "A claim expired or changed. Read and reacquire work before renewing.");
      }
      renew.add(current);
    }
    Set<Long> held = new HashSet<>();
    graph.values().stream()
        .filter(
            current ->
                profile.getId().equals(current.getClaimantId()) && current.hasActiveClaim(now))
        .forEach(current -> held.add(current.getId()));
    if (!held.equals(seen)) {
      throw conflict(
          "incomplete_claim_proofs",
          "Supply current token proofs for every active claim held by this agent.");
    }
    for (WorkItem current : renew) {
      current.renewLease(now.plus(LEASE));
      current.touch(now);
    }
    profile.seenAt(now);
    items.flush();
    return dto(profile, List.copyOf(graph.values()), now);
  }

  public ActivityDto comment(long id, CommentRequest request, String actor) {
    lock();
    actor = actor(actor);
    require(request != null, "A comment request is required.");
    if (!items.existsById(id)) throw missing("Work item not found.");
    return dto(record(id, actor, text(request.body(), "Comment", 8000, true), clock.instant()));
  }

  private void lock() {
    if (locks.acquire().isEmpty())
      throw new IllegalStateException("The workspace lock is missing. Apply database migrations.");
  }

  private Map<Long, WorkItem> graph() {
    Map<Long, WorkItem> graph = new LinkedHashMap<>();
    items.findAllByOrderByIdAsc().forEach(item -> graph.put(item.getId(), item));
    // Initialize collections before mutation so graph validation never triggers an implicit flush.
    graph
        .values()
        .forEach(
            item -> {
              item.getDependencies().size();
              item.getLabels().size();
            });
    return graph;
  }

  private void validateProof(
      WorkItem current, UUID agentId, UUID token, Instant now, boolean requireActive) {
    if (agentId == null
        || token == null
        || !agentId.equals(current.getClaimantId())
        || !token.equals(current.getClaimToken())) {
      throw conflict(
          "claim_proof_required", "Supply the current claimant agent ID and claim token.");
    }
    if (requireActive && !current.hasActiveClaim(now))
      throw conflict(
          "stale_claim", "The execution claim expired. Reacquire work before writing progress.");
  }

  private AgentProfile agent(UUID id) {
    if (id == null) throw invalid("agent_required", "An agent ID is required.");
    return agents.findById(id).orElseThrow(() -> missing("Register the agent profile first."));
  }

  private WorkItem item(Map<Long, WorkItem> graph, Long id) {
    WorkItem result = graph.get(id);
    if (result == null) throw missing("Work item " + id + " was not found.");
    return result;
  }

  private void checkVersion(WorkItem item, Long expected) {
    if (expected == null || expected < 0)
      throw invalid(
          "version_required", "Read the item and supply its current nonnegative version.");
    if (item.getVersion() != expected)
      throw conflict(
          "version_conflict",
          "This item changed. Read it again and retry with its current version.");
  }

  private ActivityEvent record(Long itemId, String actor, String message, Instant now) {
    return activity.save(new ActivityEvent(itemId, actor, message, now));
  }

  private Map<Long, WorkItem> index(List<WorkItem> work) {
    Map<Long, WorkItem> indexed = new LinkedHashMap<>();
    work.forEach(item -> indexed.put(item.getId(), item));
    return indexed;
  }

  private WorkItemDto dto(WorkItem item) {
    return dto(item, id -> items.findById(id).orElse(null));
  }

  private WorkItemDto dto(WorkItem item, java.util.function.Function<Long, WorkItem> lookup) {
    return new WorkItemDto(
        item.getId(),
        WorkItemKeys.key(item, lookup),
        item.getTitle(),
        item.getType(),
        item.getParentId(),
        item.getStatus(),
        item.getPriority(),
        item.getAssigneeId(),
        item.getClaimantId(),
        item.getClaimToken(),
        item.getLeaseUntil(),
        item.getDescription(),
        item.getAcceptance(),
        item.getOutput(),
        item.getLabels().stream().sorted().toList(),
        item.getDependencies().stream().sorted().toList(),
        item.getVersion(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        WorkItemKeys.prefix(item, lookup));
  }

  private AgentDto dto(AgentProfile agent, List<WorkItem> work, Instant now) {
    String presence =
        agent.getLastSeen().plus(LEASE).isAfter(now)
            ? work.stream()
                    .anyMatch(i -> agent.getId().equals(i.getClaimantId()) && i.hasActiveClaim(now))
                ? "working"
                : "available"
            : "offline";
    return new AgentDto(
        agent.getId(),
        agent.getName(),
        agent.getRole(),
        agent.getSubject(),
        agent.getColor(),
        agent.getLastSeen(),
        presence);
  }

  private ActivityDto dto(ActivityEvent event) {
    return new ActivityDto(
        event.getId(),
        event.getItemId(),
        event.getActor(),
        event.getMessage(),
        event.getCreatedAt());
  }

  private boolean executable(WorkItem item) {
    return item.getType() == ItemType.STORY || item.getType() == ItemType.TASK;
  }

  private String actor(String actor) {
    return text(actor, "Activity actor", 200, true);
  }

  private String orEmpty(String text) {
    return text == null ? "" : text;
  }

  private Long positiveOrNull(Long value) {
    require(value == null || value > 0, "Parent ID must be positive.");
    return value;
  }

  private String text(String value, String name, int maximum, boolean required) {
    require(
        value != null && value.length() <= maximum,
        name + " must contain at most " + maximum + " characters.");
    require(value.indexOf('\u0000') < 0, name + " must not contain null characters.");
    String trimmed = value.strip();
    require(!required || !trimmed.isEmpty(), name + " is required.");
    return trimmed;
  }

  private Set<String> labels(List<String> values) {
    if (values == null) return Set.of();
    require(values.size() <= 50, "Supply at most 50 labels.");
    Set<String> result = new LinkedHashSet<>();
    values.forEach(value -> result.add(text(value, "Label", 100, true)));
    return result;
  }

  private Set<Long> dependencies(List<Long> values) {
    if (values == null) return Set.of();
    require(values.size() <= 50, "Supply at most 50 prerequisites.");
    Set<Long> result = new LinkedHashSet<>();
    values.forEach(
        value -> {
          require(value != null && value > 0, "Prerequisite IDs must be positive.");
          result.add(value);
        });
    return result;
  }

  private void require(boolean condition, String message) {
    if (!condition) throw invalid("invalid_request", message);
  }

  private DomainException invalid(String code, String message) {
    return new DomainException(400, code, message);
  }

  private DomainException missing(String message) {
    return new DomainException(404, "not_found", message);
  }

  private DomainException conflict(String code, String message) {
    return new DomainException(409, code, message);
  }
}
