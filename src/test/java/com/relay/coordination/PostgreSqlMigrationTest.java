package com.relay.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs Flyway and Hibernate schema validation against PostgreSQL when Docker is available. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("relay_test")
          .withUsername("relay")
          .withPassword("relay-test-only");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationCreatesTheCoordinationSchemaAndSingletonLock() {
    assertNotNull(flyway.info().current());
    assertEquals("3", flyway.info().current().getVersion().getVersion());
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from workspace_lock where id = 1", Integer.class)
            .intValue());
    assertEquals(
        0, jdbc.queryForObject("select count(*) from work_items", Integer.class).intValue());
    assertEquals(
        0, jdbc.queryForObject("select count(*) from agent_profiles", Integer.class).intValue());
  }
}
