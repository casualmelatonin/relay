package com.relay.coordination.web;

import com.relay.coordination.api.dto.WorkItemDto;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.domain.WorkStatus;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Converts the planning hierarchy into a developer's ordered, incremental project plan. */
@Component
public class ProjectPlanPresenter {
  public record Plan(
      WorkItemDto project,
      List<Milestone> milestones,
      List<WorkItemDto> chunks,
      long completed,
      int progress,
      WorkItemDto nextChunk) {}

  public record Milestone(WorkItemDto item, List<WorkItemDto> chunks, long completed) {}

  public List<Plan> plans(List<WorkItemDto> items) {
    var byId = items.stream().collect(Collectors.toMap(WorkItemDto::id, Function.identity()));
    return items.stream()
        .filter(i -> i.type() == ItemType.INITIATIVE)
        .map(
            project -> {
              var chunks =
                  items.stream()
                      .filter(this::isChunk)
                      .filter(i -> belongs(i, project.id(), byId))
                      .toList();
              var milestones =
                  items.stream()
                      .filter(i -> i.type() == ItemType.EPIC && belongs(i, project.id(), byId))
                      .map(
                          m -> {
                            var group =
                                chunks.stream().filter(i -> belongs(i, m.id(), byId)).toList();
                            return new Milestone(
                                m,
                                group,
                                group.stream().filter(i -> i.status() == WorkStatus.DONE).count());
                          })
                      .toList();
              long completed = chunks.stream().filter(i -> i.status() == WorkStatus.DONE).count();
              // Show work already underway first; otherwise choose a dependency-free ready/backlog
              // chunk.
              var next =
                  chunks.stream()
                      .filter(
                          i ->
                              i.status() == WorkStatus.IN_PROGRESS
                                  || i.status() == WorkStatus.REVIEW)
                      .findFirst()
                      .orElseGet(
                          () ->
                              chunks.stream()
                                  .filter(i -> i.status() == WorkStatus.READY)
                                  .filter(i -> ready(i, byId))
                                  .findFirst()
                                  .orElseGet(
                                      () ->
                                          chunks.stream()
                                              .filter(i -> i.status() == WorkStatus.BACKLOG)
                                              .filter(i -> ready(i, byId))
                                              .findFirst()
                                              .orElse(null)));
              return new Plan(
                  project,
                  milestones,
                  chunks,
                  completed,
                  chunks.isEmpty() ? 0 : (int) (completed * 100 / chunks.size()),
                  next);
            })
        .toList();
  }

  private boolean ready(WorkItemDto item, Map<Long, WorkItemDto> items) {
    return item.dependencies().stream()
        .allMatch(id -> items.containsKey(id) && items.get(id).status() == WorkStatus.DONE);
  }

  private boolean isChunk(WorkItemDto item) {
    return item.type() == ItemType.TASK || item.type() == ItemType.STORY;
  }

  private boolean belongs(WorkItemDto item, long ancestor, Map<Long, WorkItemDto> items) {
    var visited = new HashSet<Long>();
    for (var cursor = item;
        cursor != null && visited.add(cursor.id());
        cursor = cursor.parentId() == null ? null : items.get(cursor.parentId())) {
      if (cursor.id() == ancestor) return true;
    }
    return false;
  }
}
