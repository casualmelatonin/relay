package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Backfills project ticket numbers without changing IDs, parents, dependencies, or guides. */
public final class V4__project_ticket_sequences extends BaseJavaMigration {
  private record Existing(long id, Long parent, boolean project, String prefix) {}

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE ticket_sequences (scope_id BIGINT PRIMARY KEY, prefix VARCHAR(12) NOT NULL UNIQUE, next_number BIGINT NOT NULL CHECK (next_number > 0))");
      statement.execute("ALTER TABLE work_items ADD COLUMN ticket_prefix VARCHAR(12)");
      statement.execute("ALTER TABLE work_items ADD COLUMN ticket_number BIGINT");
    }
    Map<Long, Existing> items = new LinkedHashMap<>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT id, parent_id, item_type, key_prefix FROM work_items ORDER BY id")) {
      while (rows.next()) {
        long parent = rows.getLong("parent_id");
        Long parentId = rows.wasNull() ? null : parent;
        items.put(
            rows.getLong("id"),
            new Existing(
                rows.getLong("id"),
                parentId,
                "INITIATIVE".equals(rows.getString("item_type")),
                rows.getString("key_prefix")));
      }
    }
    Map<Long, String> prefixes = new LinkedHashMap<>();
    prefixes.put(0L, "WORK");
    Set<String> used = new HashSet<>(prefixes.values());
    for (Existing item : items.values()) {
      if (!item.project()) continue;
      String base =
          item.prefix() == null || item.prefix().isBlank()
              ? "RLY"
              : item.prefix().strip().toUpperCase(Locale.ROOT);
      if (!base.matches("[A-Z][A-Z0-9]{1,11}")) base = "RLY";
      String prefix = base;
      int suffix = 2;
      while (used.contains(prefix)) {
        String tail = String.valueOf(suffix++);
        prefix = base.substring(0, Math.min(base.length(), 12 - tail.length())) + tail;
      }
      used.add(prefix);
      prefixes.put(item.id(), prefix);
    }
    Map<Long, Long> next = new HashMap<>();
    prefixes.keySet().forEach(scope -> next.put(scope, 1L));
    try (var update =
        connection.prepareStatement(
            "UPDATE work_items SET ticket_prefix = ?, ticket_number = ?, key_prefix = ? WHERE id = ?")) {
      // Reserve each project's first number for the project record itself.
      for (Existing item : items.values()) {
        if (!item.project()) continue;
        write(update, item, prefixes.get(item.id()), 1);
        next.put(item.id(), 2L);
      }
      for (Existing item : items.values()) {
        if (item.project()) continue;
        long scope = scope(item, items);
        long number = next.get(scope);
        write(update, item, prefixes.get(scope), number);
        next.put(scope, number + 1);
      }
    }
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO ticket_sequences (scope_id, prefix, next_number) VALUES (?, ?, ?)")) {
      for (var entry : prefixes.entrySet()) {
        insert.setLong(1, entry.getKey());
        insert.setString(2, entry.getValue());
        insert.setLong(3, next.get(entry.getKey()));
        insert.executeUpdate();
      }
    }
    try (var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE work_items ALTER COLUMN ticket_prefix SET NOT NULL");
      statement.execute("ALTER TABLE work_items ALTER COLUMN ticket_number SET NOT NULL");
      statement.execute(
          "ALTER TABLE work_items ADD CONSTRAINT uq_ticket_key UNIQUE (ticket_prefix, ticket_number)");
      statement.execute(
          "ALTER TABLE work_items ADD CONSTRAINT ck_ticket_number CHECK (ticket_number > 0)");
    }
  }

  private void write(java.sql.PreparedStatement update, Existing item, String prefix, long number)
      throws SQLException {
    update.setString(1, prefix);
    update.setLong(2, number);
    update.setString(3, item.project() ? prefix : null);
    update.setLong(4, item.id());
    update.executeUpdate();
  }

  private long scope(Existing item, Map<Long, Existing> items) throws SQLException {
    Set<Long> visited = new HashSet<>();
    Existing current = item;
    while (current != null && visited.add(current.id())) {
      if (current.project()) return current.id();
      if (current.parent() == null) return 0;
      current = items.get(current.parent());
    }
    throw new SQLException("Cannot migrate a missing parent or cyclic work hierarchy.");
  }
}
