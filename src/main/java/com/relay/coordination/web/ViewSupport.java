package com.relay.coordination.web;

import com.relay.coordination.api.dto.AgentDto;
import com.relay.coordination.api.dto.WorkItemDto;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.WorkStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Presentation-only formatting. Coordination rules stay in the application service. */
@Component("ui")
public final class ViewSupport {
  private final Clock clock;

  public ViewSupport(Clock clock) {
    this.clock = clock;
  }

  public String label(Object value) {
    if (value == null) return "Unassigned";
    if (value instanceof ItemType type)
      return switch (type) {
        case INITIATIVE -> "Project";
        case EPIC -> "Milestone";
        case STORY -> "Story";
        case TASK -> "Chunk";
      };
    String text = value.toString().replace('_', ' ').toLowerCase(Locale.ROOT);
    return text.isBlank() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  public String css(Object value) {
    return value == null ? "none" : value.toString().toLowerCase(Locale.ROOT);
  }

  public String initials(String value) {
    if (value == null || value.isBlank()) return "?";
    String[] parts = value.trim().split("[\\s-]+");
    return (parts[0].substring(0, 1)
            + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : ""))
        .toUpperCase(Locale.ROOT);
  }

  public String ago(Instant when) {
    if (when == null) return "Not seen yet";
    long minutes = Math.max(0, Duration.between(when, clock.instant()).toMinutes());
    if (minutes < 1) return "just now";
    if (minutes < 60) return minutes + "m ago";
    if (minutes < 1440) return minutes / 60 + "h ago";
    return minutes / 1440 + "d ago";
  }

  public String date(Instant when) {
    return when == null
        ? "—"
        : DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH)
            .withZone(ZoneId.of("UTC"))
            .format(when);
  }

  public String typeIcon(ItemType type) {
    return switch (type) {
      case INITIATIVE -> "#target";
      case EPIC -> "#zap";
      case STORY -> "#book";
      case TASK -> "#checklist";
    };
  }

  public boolean work(WorkItemDto item) {
    return item.type() == ItemType.STORY || item.type() == ItemType.TASK;
  }

  public long totalWork(List<WorkItemDto> items) {
    return items.stream().filter(this::work).count();
  }

  public long count(List<WorkItemDto> items, String status) {
    return items.stream().filter(this::work).filter(i -> i.status().name().equals(status)).count();
  }

  public int progress(List<WorkItemDto> items) {
    long total = totalWork(items);
    return total == 0 ? 0 : (int) Math.round(count(items, "DONE") * 100.0 / total);
  }

  public boolean leased(WorkItemDto item) {
    return item.leaseUntil() != null && item.leaseUntil().isAfter(clock.instant());
  }

  public List<WorkItemDto> blockers(WorkItemDto item, Map<Long, WorkItemDto> items) {
    return item.dependencies().stream()
        .map(items::get)
        .filter(java.util.Objects::nonNull)
        .filter(i -> i.status() != WorkStatus.DONE)
        .toList();
  }

  public boolean claimable(WorkItemDto item) {
    return work(item)
        && (item.status() == WorkStatus.READY || item.status() == WorkStatus.IN_PROGRESS);
  }

  public String agentName(UUID id, Map<UUID, AgentDto> agents) {
    AgentDto agent = id == null ? null : agents.get(id);
    return agent == null ? "Unassigned" : agent.name();
  }

  public String agentInitials(UUID id, Map<UUID, AgentDto> agents) {
    AgentDto agent = id == null ? null : agents.get(id);
    return agent == null ? "?" : initials(agent.name());
  }

  public String safeColor(String value) {
    return value != null && value.matches("#[a-fA-F0-9]{6}") ? value : "#8b7ca6";
  }

  public String value(Map<String, String> form, String key, Object fallback) {
    return form != null && form.containsKey(key)
        ? form.get(key)
        : fallback == null ? "" : fallback.toString();
  }

  public String join(List<?> values) {
    return values == null ? "" : String.join(", ", values.stream().map(Object::toString).toList());
  }

  public String dependencyKeys(WorkItemDto item, Map<Long, WorkItemDto> items) {
    return String.join(
        ", ",
        item.dependencies().stream()
            .map(id -> items.containsKey(id) ? items.get(id).key() : id.toString())
            .toList());
  }

  public long assignedCount(UUID id, List<WorkItemDto> items) {
    return items.stream().filter(this::work).filter(i -> id.equals(i.assigneeId())).count();
  }

  public long activeCount(UUID id, List<WorkItemDto> items) {
    return items.stream()
        .filter(this::work)
        .filter(i -> id.equals(i.assigneeId()))
        .filter(i -> i.status() == WorkStatus.IN_PROGRESS || i.status() == WorkStatus.BLOCKED)
        .count();
  }

  public long doneCount(UUID id, List<WorkItemDto> items) {
    return items.stream()
        .filter(this::work)
        .filter(i -> id.equals(i.assigneeId()))
        .filter(i -> i.status() == WorkStatus.DONE)
        .count();
  }

  public List<WorkItemDto> focus(UUID id, List<WorkItemDto> items) {
    return items.stream()
        .filter(this::work)
        .filter(i -> id.equals(i.assigneeId()))
        .filter(i -> i.status() == WorkStatus.IN_PROGRESS || i.status() == WorkStatus.BLOCKED)
        .limit(2)
        .toList();
  }
}
