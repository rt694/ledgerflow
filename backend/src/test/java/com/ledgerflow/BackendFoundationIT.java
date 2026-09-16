package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BackendFoundationIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17.10")
          .withDatabaseName("ledgerflow")
          .withUsername("ledgerflow");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired Flyway flyway;

  @Test
  void migrationRunsOnceAndRevalidates() {
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'ledgerflow'",
                Integer.class))
        .isEqualTo(1);
    assertThat(flyway.info().applied()).hasSize(1);
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void freshFlywayConnectionUsesExistingHistoryAfterSchemaCreation() {
    Flyway fresh =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .defaultSchema("public")
            .load();
    assertThat(fresh.migrate().migrationsExecuted).isZero();
    assertThat(fresh.info().applied()).hasSize(1);
  }

  @Test
  void healthIncludesDatabaseCheckButHidesDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
  }

  @Test
  void openApiDescribesEndpointAndValidation() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("LedgerFlow API"))
        .andExpect(jsonPath("$.paths['/api/v1/greetings'].post").exists())
        .andExpect(
            jsonPath("$.components.schemas.GreetingRequest.properties.name.maxLength").value(80));
  }

  @Test
  void swaggerUiIsServed() throws Exception {
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
