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
        .info(
            new Info()
                .title("LedgerFlow API")
                .version("v1")
                .description("Learning project. Synthetic data and sandbox integrations only."));
  }
}
