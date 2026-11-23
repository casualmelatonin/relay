package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DeletionMigrationTest {
  @Test
  void upgradesExistingHistoryAndGuidesWithoutRemovingGraphConstraints() throws Exception {
    verifyUpgrade(
        "jdbc:h2:mem:delete-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "public");
  }

  static void verifyUpgrade(String url, String user, String password, String schema)
      throws Exception {
    Flyway.configure().dataSource(url, user, password).schemas(schema).target("4").load().migrate();
    UUID event = UUID.randomUUID();
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.setSchema(schema);
      try (var statement = connection.createStatement()) {
        for (int id : new int[] {1, 2})
          statement.execute(
              "INSERT INTO work_items (id, title, item_type, parent_id, status, priority, description, acceptance, output, created_at, updated_at, ticket_prefix, ticket_number) "
                  + "VALUES ("
                  + id
                  + ", 'Existing chunk', 'TASK', "
                  + (id == 1 ? "NULL" : "1")
                  + ", 'BACKLOG', 'MEDIUM', '', '', '', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'WORK', "
                  + id
                  + ")");
        statement.execute(
            "INSERT INTO chunk_guides VALUES (2, 0, 'Purpose', 'Goal', 'Steps', 'Notes')");
        statement.execute("INSERT INTO work_item_labels VALUES (2, 'old-label')");
        statement.execute("INSERT INTO work_item_dependencies VALUES (2, 1)");
        statement.execute(
            "INSERT INTO activity_events VALUES ('"
                + event
                + "', 2, 'workspace', 'Original note', CURRENT_TIMESTAMP)");
      }
      Flyway.configure().dataSource(url, user, password).schemas(schema).load().migrate();
      try (var statement = connection.createStatement()) {
        assertThrows(
            SQLException.class, () -> statement.execute("DELETE FROM work_items WHERE id = 1"));
        statement.execute("DELETE FROM work_items WHERE id = 2");
        for (String table :
            new String[] {"chunk_guides", "work_item_labels", "work_item_dependencies"}) {
          try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
          }
        }
        try (var rows =
            statement.executeQuery(
                "SELECT item_id, actor, message FROM activity_events WHERE id = '" + event + "'")) {
          assertTrue(rows.next());
          assertEquals(2, rows.getLong(1));
          assertEquals("workspace", rows.getString(2));
          assertEquals("Original note", rows.getString(3));
        }
        statement.execute("DELETE FROM work_items WHERE id = 1");
      }
    }
    assertEquals(
        0,
        Flyway.configure()
            .dataSource(url, user, password)
            .schemas(schema)
            .load()
            .migrate()
            .migrationsExecuted);
  }
}
