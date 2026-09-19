package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
abstract class IntegrationTestSupport {
  // One real PostgreSQL instance for this test JVM. Ryuk cleans it up on JVM exit.
  // Starting explicitly makes unavailable Docker a failure, not a skipped test.
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17.10")
          .withDatabaseName("ledgerflow")
          .withUsername("ledgerflow");
  static final Path keys;

  static {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      keys = Files.createTempDirectory("ledgerflow-test-keys-");
      keys.toFile().deleteOnExit();
      writePem(keys.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
      writePem(keys.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
      postgres.start();
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void writePem(Path file, String type, byte[] bytes) throws Exception {
    String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
    Files.writeString(
        file, "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n");
    file.toFile().deleteOnExit();
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(
        "ledgerflow.security.jwt.private-key", () -> "file:" + keys.resolve("private.pem"));
    registry.add("ledgerflow.security.jwt.public-key", () -> "file:" + keys.resolve("public.pem"));
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  JsonNode send(HttpMethod method, String path, String token, Object body, int expected)
      throws Exception {
    var builder = request(method, path);
    if (token != null) builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (body != null)
      builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var response = mvc.perform(builder).andReturn().getResponse();
    assertThat(response.getStatus())
        .as(method + " " + path + " response: " + response.getContentAsString())
        .isEqualTo(expected);
    if (response.getContentAsString().isBlank()) return json.nullNode();
    return json.readTree(response.getContentAsString());
  }

  TestUser register(String name) throws Exception {
    String email = name + UUID.randomUUID() + "@example.test";
    JsonNode user =
        send(
            HttpMethod.POST,
            "/api/v1/auth/register",
            null,
            Map.of("email", email, "displayName", name, "password", "synthetic-password-123"),
            201);
    JsonNode login =
        send(
            HttpMethod.POST,
            "/api/v1/auth/login",
            null,
            Map.of("email", email, "password", "synthetic-password-123"),
            200);
    return new TestUser(
        UUID.fromString(user.get("id").asText()), email, login.get("accessToken").asText());
  }

  String organization(TestUser owner) throws Exception {
    return send(
            HttpMethod.POST,
            "/api/v1/organizations",
            owner.token(),
            Map.of("name", "Synthetic shop"),
            201)
        .get("id")
        .asText();
  }

  void addMember(String org, TestUser owner, TestUser member, String role) throws Exception {
    send(
        HttpMethod.POST,
        "/api/v1/organizations/" + org + "/members",
        owner.token(),
        Map.of("email", member.email(), "role", role),
        201);
  }

  String base(String org) {
    return "/api/v1/organizations/" + org;
  }

  record TestUser(UUID id, String email, String token) {}
}
