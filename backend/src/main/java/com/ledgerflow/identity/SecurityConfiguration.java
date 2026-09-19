package com.ledgerflow.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectMapper json,
      @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .logout(logout -> logout.disable())
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login")
                  .permitAll();
              auth.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/stripe").permitAll();
              auth.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/plaid").permitAll();
              auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
              if (docsEnabled)
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll();
              auth.anyRequest().authenticated();
            })
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(json, request, response, 401))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(json, request, response, 403)))
        .oauth2ResourceServer(
            resource ->
                resource
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(json, request, response, 401))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(json, request, response, 403)));
    return http.build();
  }

  private void writeProblem(
      ObjectMapper json, HttpServletRequest request, HttpServletResponse response, int status)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(status),
            status == 401 ? "A valid bearer token is required." : "This action is not allowed.");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "HTTP_" + status);
    json.writeValue(response.getOutputStream(), problem);
  }
}
