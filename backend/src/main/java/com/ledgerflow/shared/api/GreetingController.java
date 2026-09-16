package com.ledgerflow.shared.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Temporary, stateless endpoint for exercising the API foundation. */
@RestController
@RequestMapping("/api/v1/greetings")
public class GreetingController {
  @Operation(summary = "Try the API", description = "Stateless example; does not save data.")
  @ApiResponse(responseCode = "200", description = "Greeting returned")
  @ApiResponse(responseCode = "400", description = "Invalid request")
  @PostMapping
  public GreetingResponse greet(@Valid @RequestBody GreetingRequest request) {
    return new GreetingResponse("Hello, " + request.name().strip() + "!");
  }

  public record GreetingRequest(
      @NotBlank(message = "Name is required")
          @Size(max = 80, message = "Name must be at most 80 characters")
          String name) {}

  public record GreetingResponse(String message) {}
}
