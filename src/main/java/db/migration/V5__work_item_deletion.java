package db.migration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep immutable audit references after deletion; cascade only the item's owned guide. */
public final class V5__work_item_deletion extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    dropItemForeignKey(connection, "activity_events");
    dropItemForeignKey(connection, "chunk_guides");
    try (var statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE chunk_guides ADD CONSTRAINT fk_chunk_guide_item "
              + "FOREIGN KEY (item_id) REFERENCES work_items(id) ON DELETE CASCADE");
    }
  }

  private void dropItemForeignKey(Connection connection, String table) throws Exception {
    List<String> constraints = new ArrayList<>();
    // V1/V2 used database-generated names, which differ between H2 and PostgreSQL.
    try (var keys =
        connection
            .getMetaData()
            .getImportedKeys(connection.getCatalog(), connection.getSchema(), table)) {
      while (keys.next()) {
        if ("item_id".equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
            && "work_items".equalsIgnoreCase(keys.getString("PKTABLE_NAME")))
          constraints.add(keys.getString("FK_NAME"));
      }
    }
    if (constraints.size() != 1)
      throw new IllegalStateException("Expected one work-item foreign key on " + table);
    String quote = connection.getMetaData().getIdentifierQuoteString();
    try (var statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE "
              + table
              + " DROP CONSTRAINT "
              + quote
              + constraints.getFirst().replace(quote, quote + quote)
              + quote);
    }
  }
}
