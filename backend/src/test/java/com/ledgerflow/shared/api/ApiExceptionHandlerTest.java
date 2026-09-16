package com.ledgerflow.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {
  @Test
  void unexpectedErrorsHideSensitiveExceptionDetails() throws Exception {
    var mvc =
        MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    var response =
        mvc.perform(get("/test/failure"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(jsonPath("$.code").value("HTTP_500"))
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsString())
        .doesNotContain("private-password", "IllegalStateException");
  }

  @RestController
  static class FailingController {
    @GetMapping("/test/failure")
    String fail() {
      throw new IllegalStateException("private-password");
    }
  }
}
