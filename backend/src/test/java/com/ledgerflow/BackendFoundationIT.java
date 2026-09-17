package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class BackendFoundationIT extends IntegrationTestSupport {
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
    assertThat(flyway.info().applied()).hasSize(3);
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
    assertThat(fresh.info().applied()).hasSize(3);
  }

  @Test
  void healthIncludesDatabaseCheckButHidesDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(
            get("/actuator/env")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void openApiDescribesEndpointAndValidation() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("LedgerFlow API"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.security").isEmpty())
        .andExpect(
            jsonPath("$.components.schemas.OrganizationCreateRequest.properties.name").exists())
        .andExpect(jsonPath("$.components.schemas.CustomerCreateRequest.properties.email").exists())
        .andExpect(
            jsonPath("$.components.schemas.InvoiceCreateRequest.properties.customerId").exists())
        .andExpect(jsonPath("$.paths['/api/v1/greetings'].post").exists())
        .andExpect(
            jsonPath("$.components.schemas.GreetingRequest.properties.name.maxLength").value(80));
  }

  @Test
  void swaggerUiIsServed() throws Exception {
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }
}
