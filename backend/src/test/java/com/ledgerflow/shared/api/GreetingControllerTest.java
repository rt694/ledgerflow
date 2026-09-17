package com.ledgerflow.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GreetingController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class GreetingControllerTest {
  @Autowired MockMvc mvc;

  @Test
  void returnsGreetingWithoutSavingAnything() throws Exception {
    mvc.perform(
            post("/api/v1/greetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" Student \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Hello, Student!"));
  }

  @Test
  void blankNameReturnsFieldErrorWithoutRejectedValue() throws Exception {
    mvc.perform(
            post("/api/v1/greetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("HTTP_400"))
        .andExpect(jsonPath("$.instance").value("/api/v1/greetings"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].message").value("Name is required"))
        .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
  }

  @Test
  void missingNameIsRejected() throws Exception {
    mvc.perform(post("/api/v1/greetings").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oversizedNameIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/greetings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "a".repeat(81) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("Name must be at most 80 characters"));
  }

  @Test
  void malformedJsonDoesNotLeakParserDetails() throws Exception {
    var response =
        mvc.perform(
                post("/api/v1/greetings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{secret-token"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("The request is invalid."))
            .andReturn()
            .getResponse();
    assertThat(response.getContentAsString()).doesNotContain("secret-token", "Exception");
  }

  @Test
  void unsupportedMethodPreservesAllowHeader() throws Exception {
    mvc.perform(get("/api/v1/greetings"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(jsonPath("$.status").value(405));
  }

  @Test
  void unsupportedContentTypeUsesProblemResponse() throws Exception {
    mvc.perform(post("/api/v1/greetings").contentType(MediaType.TEXT_PLAIN).content("Student"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.status").value(415));
  }

  @Test
  void missingResourceUsesProblemResponse() throws Exception {
    mvc.perform(get("/api/v1/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }
}
