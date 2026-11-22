package com.relay.coordination.service;

import com.relay.coordination.domain.DomainException;
import com.relay.coordination.domain.ItemType;
import com.relay.coordination.persistence.WorkItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Project-owned prefixes; numeric IDs and dependency relationships remain stable. */
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

  static String prefix(WorkItem item, Function<Long, WorkItem> lookup) {
    Set<Long> visited = new HashSet<>();
    WorkItem current = item;
    while (current != null && visited.add(current.getId())) {
      if (current.getType() == ItemType.INITIATIVE)
        return current.getKeyPrefix() == null ? "RLY" : current.getKeyPrefix();
      current = current.getParentId() == null ? null : lookup.apply(current.getParentId());
    }
    return "RLY";
  }

  static String key(WorkItem item, Function<Long, WorkItem> lookup) {
    return prefix(item, lookup) + "-" + item.getId();
  }
}
