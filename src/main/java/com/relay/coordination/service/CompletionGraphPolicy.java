package com.relay.coordination.service;

import com.relay.coordination.domain.*;
import com.relay.coordination.persistence.WorkItem;
import java.util.*;
import org.springframework.stereotype.Component;

/** Pure graph policy, isolated from transactions, authentication, and transport adapters. */
@Component
final class CompletionGraphPolicy {
  void validateParent(WorkItem child, Map<Long, WorkItem> graph) {
    if (child.getParentId() == null) return;
    WorkItem parent = item(graph, child.getParentId());
    boolean allowed =
        switch (child.getType()) {
          case INITIATIVE -> false;
          case EPIC -> parent.getType() == ItemType.INITIATIVE;
          case STORY -> parent.getType() == ItemType.EPIC;
          case TASK ->
              parent.getType() == ItemType.EPIC
                  || parent.getType() == ItemType.STORY
                  || parent.getType() == ItemType.TASK;
        };
    require(
        allowed,
        "Use initiative → epic → story → task hierarchy; tasks may also nest under epics or tasks.");
    if (child.getId() != null && child.getId().equals(parent.getId()))
      throw invalid("graph_cycle", "An item cannot be its own parent.");
    if (child.getStatus() != WorkStatus.DONE && parent.getStatus() == WorkStatus.DONE) {
      throw conflict(
          "completed_parent",
          "Reopen the parent before adding or reopening unfinished child work.");
    }
  }

  void validateGraph(Map<Long, WorkItem> graph) {
    Map<Long, Set<Long>> edges = new HashMap<>();
    Map<Long, Integer> inDegree = new HashMap<>();
    for (WorkItem current : graph.values()) {
      validateParent(current, graph);
      edges.put(current.getId(), new LinkedHashSet<>(current.getDependencies()));
      inDegree.put(current.getId(), 0);
    }
    // A parent waits for children to finish. Including these edges catches ancestor and
    // cross-branch deadlocks.
    for (WorkItem current : graph.values()) {
      if (current.getParentId() != null) edges.get(current.getParentId()).add(current.getId());
    }
    for (Set<Long> prerequisites : edges.values()) {
      for (Long dependency : prerequisites) {
        item(graph, dependency);
        inDegree.compute(dependency, (key, count) -> count + 1);
      }
    }
    ArrayDeque<Long> ready = new ArrayDeque<>();
    inDegree.forEach(
        (id, count) -> {
          if (count == 0) ready.add(id);
        });
    int visited = 0;
    while (!ready.isEmpty()) {
      Long id = ready.removeFirst();
      visited++;
      for (Long dependency : edges.get(id)) {
        int remaining = inDegree.compute(dependency, (key, count) -> count - 1);
        if (remaining == 0) ready.addLast(dependency);
      }
    }
    if (visited != graph.size())
      throw invalid(
          "graph_cycle", "Hierarchy and prerequisites would create a completion deadlock.");
    for (WorkItem current : graph.values()) {
      if (current.getStatus() == WorkStatus.IN_PROGRESS
          || current.getStatus() == WorkStatus.REVIEW
          || current.getStatus() == WorkStatus.DONE) {
        requireDependenciesDone(current, graph);
      }
      if (current.getStatus() == WorkStatus.DONE) {
        if (executable(current) && current.getOutput().isBlank())
          throw invalid(
              "output_required",
              "Add an output or completion summary before closing a story or task.");
        if (graph.values().stream()
            .anyMatch(
                child ->
                    current.getId().equals(child.getParentId())
                        && child.getStatus() != WorkStatus.DONE)) {
          throw conflict("unfinished_children", "Complete child work before closing a parent.");
        }
      }
    }
  }

  void requireDependenciesDone(WorkItem current, Map<Long, WorkItem> graph) {
    List<String> blockers =
        current.getDependencies().stream()
            .filter(id -> item(graph, id).getStatus() != WorkStatus.DONE)
            .map(id -> "RLY-" + id)
            .sorted()
            .toList();
    if (!blockers.isEmpty())
      throw conflict(
          "unfinished_dependencies",
          "Complete prerequisites first: " + String.join(", ", blockers) + ".");
  }

  private WorkItem item(Map<Long, WorkItem> graph, Long id) {
    WorkItem result = graph.get(id);
    if (result == null)
      throw new DomainException(404, "not_found", "Work item " + id + " was not found.");
    return result;
  }

  private boolean executable(WorkItem item) {
    return item.getType() == ItemType.TASK || item.getType() == ItemType.STORY;
  }

  private void require(boolean condition, String message) {
    if (!condition) throw invalid("invalid_request", message);
  }

  private DomainException invalid(String code, String message) {
    return new DomainException(400, code, message);
  }

  private DomainException conflict(String code, String message) {
    return new DomainException(409, code, message);
  }
}
