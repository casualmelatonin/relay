package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ProjectTicketMigrationTest {
  @Test
  void migratesLegacyPrefixesAndPreservesTicketRelationshipsAndNotes() throws Exception {
    String url =
        "jdbc:h2:mem:migration-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    Flyway.configure().dataSource(url, "sa", "").target("3").load().migrate();
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      insert(connection, 10, "INITIATIVE", null, "HTTP");
      insert(connection, 11, "EPIC", 10L, null);
      insert(connection, 12, "TASK", 11L, null);
      insert(connection, 20, "INITIATIVE", null, "http");
      insert(connection, 21, "EPIC", 20L, null);
      insert(connection, 30, "INITIATIVE", null, "WORK");
      insert(connection, 40, "TASK", null, null);
      try (var statement = connection.createStatement()) {
        statement.execute("INSERT INTO work_item_dependencies VALUES (12, 11)");
        statement.execute(
            "INSERT INTO chunk_guides VALUES (12, 0, 'Purpose', 'Goal', 'Steps', 'Notes')");
      }
      Flyway.configure().dataSource(url, "sa", "").load().migrate();
      assertKey(connection, 10, "HTTP", 1);
      assertKey(connection, 11, "HTTP", 2);
      assertKey(connection, 12, "HTTP", 3);
      assertKey(connection, 20, "HTTP2", 1);
      assertKey(connection, 21, "HTTP2", 2);
      assertKey(connection, 30, "WORK2", 1);
      assertKey(connection, 40, "WORK", 1);
      assertEquals(11L, scalar(connection, "SELECT parent_id FROM work_items WHERE id = 12"));
      assertEquals(
          11L,
          scalar(
              connection,
              "SELECT prerequisite_id FROM work_item_dependencies WHERE work_item_id = 12"));
      assertEquals(
          4L, scalar(connection, "SELECT next_number FROM ticket_sequences WHERE scope_id = 10"));
      assertEquals(
          2L, scalar(connection, "SELECT next_number FROM ticket_sequences WHERE scope_id = 0"));
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery("SELECT reflection FROM chunk_guides WHERE item_id = 12")) {
        assertTrue(rows.next());
        assertEquals("Notes", rows.getString(1));
      }
      assertThrows(
          SQLException.class,
          () -> {
            try (var statement = connection.createStatement()) {
              statement.execute(
                  "UPDATE work_items SET ticket_prefix = 'HTTP', ticket_number = 2 WHERE id = 40");
            }
          });
      assertEquals(
          0, Flyway.configure().dataSource(url, "sa", "").load().migrate().migrationsExecuted);
    }
  }

  private void insert(Connection connection, long id, String type, Long parent, String prefix)
      throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO work_items (id, title, item_type, parent_id, status, priority, description, acceptance, output, created_at, updated_at, key_prefix) VALUES (?, 'Legacy ticket', ?, ?, 'BACKLOG', 'MEDIUM', '', '', '', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)")) {
      insert.setLong(1, id);
      insert.setString(2, type);
      insert.setObject(3, parent);
      insert.setString(4, prefix);
      insert.executeUpdate();
    }
  }

  private void assertKey(Connection connection, long id, String prefix, long number)
      throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT ticket_prefix, ticket_number FROM work_items WHERE id = " + id)) {
      assertTrue(rows.next());
      assertEquals(prefix, rows.getString(1));
      assertEquals(number, rows.getLong(2));
    }
  }

  private long scalar(Connection connection, String query) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(query)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
