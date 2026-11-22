package com.relay.coordination.service;

import com.relay.coordination.domain.DomainException;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.persistence.WorkItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Resolves project ownership while keeping database IDs separate from public ticket keys. */
final class WorkItemKeys {
  private WorkItemKeys() {}

  static String normalize(String value) {
    String prefix =
        value == null || value.isBlank() ? "RLY" : value.strip().toUpperCase(Locale.ROOT);
    if (!prefix.matches("[A-Z][A-Z0-9]{1,11}"))
      throw new DomainException(
          400,
          "invalid_key_prefix",
          "Ticket prefix must be 2–12 letters or digits, starting with a letter.");
    return prefix;
  }

  static long scope(WorkItem item, Function<Long, WorkItem> lookup) {
    Set<Long> visited = new HashSet<>();
    WorkItem current = item;
    while (current != null && visited.add(current.getId())) {
      if (current.getType() == ItemType.INITIATIVE) return current.getId();
      current = current.getParentId() == null ? null : lookup.apply(current.getParentId());
    }
    return 0;
  }

  static String prefix(WorkItem item) {
    return item.getTicketPrefix();
  }

  static String key(WorkItem item) {
    return item.getTicketPrefix() + "-" + item.getTicketNumber();
  }
}
