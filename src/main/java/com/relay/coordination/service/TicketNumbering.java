package com.relay.coordination.service;

import com.relay.coordination.domain.DomainException;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.persistence.TicketSequence;
import com.relay.coordination.persistence.TicketSequenceRepository;
import com.relay.coordination.persistence.WorkItem;
import java.util.Comparator;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Allocates keys inside CoordinationService's transaction and workspace database lock. */
@Component
final class TicketNumbering {
  private final TicketSequenceRepository sequences;

  TicketNumbering(TicketSequenceRepository sequences) {
    this.sequences = sequences;
  }

  void assignNew(WorkItem item, String requestedPrefix, Map<Long, WorkItem> graph) {
    if (item.getType() == ItemType.INITIATIVE) {
      String prefix = availablePrefix(requestedPrefix);
      item.setKeyPrefix(prefix);
      item.assignTicketKey(prefix, 1);
    } else {
      allocate(item, WorkItemKeys.scope(item, graph::get));
    }
  }

  void registerProject(WorkItem item) {
    if (item.getType() == ItemType.INITIATIVE)
      sequences.save(new TicketSequence(item.getId(), item.getKeyPrefix(), 2));
  }

  void renameProject(WorkItem project, String requestedPrefix, Map<Long, WorkItem> graph) {
    String prefix = WorkItemKeys.normalize(requestedPrefix);
    TicketSequence sequence = sequence(project.getId());
    if (prefix.equals(sequence.getPrefix())) return;
    requireAvailable(prefix);
    sequence.rename(prefix);
    project.setKeyPrefix(prefix);
    for (WorkItem item : graph.values()) {
      if (WorkItemKeys.scope(item, graph::get) == project.getId())
        item.assignTicketKey(prefix, item.getTicketNumber());
    }
  }

  void reconcileMovedWork(Map<Long, WorkItem> graph) {
    graph.values().stream()
        .sorted(Comparator.comparing(WorkItem::getId))
        .forEach(
            item -> {
              long scope = WorkItemKeys.scope(item, graph::get);
              if (!sequence(scope).getPrefix().equals(item.getTicketPrefix()))
                allocate(item, scope);
            });
  }

  private void allocate(WorkItem item, long scope) {
    TicketSequence sequence = sequence(scope);
    item.assignTicketKey(sequence.getPrefix(), sequence.allocate());
  }

  private String availablePrefix(String requested) {
    String prefix = WorkItemKeys.normalize(requested);
    if (requested != null && !requested.isBlank()) {
      requireAvailable(prefix);
      return prefix;
    }
    int suffix = 2;
    while (sequences.existsByPrefix(prefix)) prefix = "RLY" + suffix++;
    return prefix;
  }

  private void requireAvailable(String prefix) {
    if (sequences.existsByPrefix(prefix))
      throw new DomainException(
          409,
          "prefix_in_use",
          "Ticket prefix " + prefix + " is already in use. Choose a unique project prefix.");
  }

  private TicketSequence sequence(long scope) {
    return sequences
        .findById(scope)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The ticket sequence is missing. Apply database migrations."));
  }
}
