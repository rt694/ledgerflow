package com.ledgerflow.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
  @Bean
  public OpenAPI ledgerFlowOpenApi() {
    return new OpenAPI()
        .components(
            new io.swagger.v3.oas.models.Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new io.swagger.v3.oas.models.security.SecurityScheme()
                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(
            new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"))
        .info(
            new Info()
                .title("LedgerFlow API")
                .version("v1")
                .description("Learning project. Synthetic data and sandbox integrations only."));
  }
}
