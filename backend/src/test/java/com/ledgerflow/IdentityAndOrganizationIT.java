package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityAndOrganizationIT extends IntegrationTestSupport {
  TestUser owner;
  TestUser employee;
  TestUser accountant;
  String org;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtEncoder encoder;
  @Autowired JwtDecoder decoder;

  @BeforeAll
  void users() throws Exception {
    owner = register("owner");
    employee = register("employee");
    accountant = register("accountant");
  }

  @BeforeEach
  void setup() throws Exception {
    org = organization(owner);
    addMember(org, owner, employee, "EMPLOYEE");
    addMember(org, owner, accountant, "ACCOUNTANT");
  }

  @Test
  void passwordIsHashedAndNeverReturned() throws Exception {
    String hash =
        jdbc.queryForObject(
            "SELECT password_hash FROM ledgerflow.users WHERE id = ?", String.class, owner.id());
    assertThat(hash).startsWith("$2a$12$").doesNotContain("synthetic-password");
    var me = send(GET, "/api/v1/auth/me", owner.token(), null, 200);
    assertThat(me.get("id").asText()).isEqualTo(owner.id().toString());
    assertThat(me.has("passwordHash")).isFalse();
    assertThat(me.has("password")).isFalse();
    Jwt token = decoder.decode(owner.token());
    assertThat(token.getExpiresAt().getEpochSecond() - token.getIssuedAt().getEpochSecond())
        .isEqualTo(900);
    assertThat(token.getClaimAsStringList("roles")).isNull();
  }

  @Test
  void emailIsNormalizedAndDuplicateRegistrationConflicts() throws Exception {
    send(
        POST,
        "/api/v1/auth/register",
        null,
        Map.of(
            "email",
            owner.email().toUpperCase(),
            "displayName",
            "Duplicate",
            "password",
            "synthetic-password-123"),
        409);
    send(
        POST,
        "/api/v1/auth/login",
        null,
        Map.of("email", owner.email().toUpperCase(), "password", "synthetic-password-123"),
        200);
  }

  @Test
  void badCredentialsGiveTheSameResponseForKnownAndUnknownUsers() throws Exception {
    var known =
        send(
            POST,
            "/api/v1/auth/login",
            null,
            Map.of("email", owner.email(), "password", "wrong-password"),
            401);
    var unknown =
        send(
            POST,
            "/api/v1/auth/login",
            null,
            Map.of("email", "missing@example.test", "password", "wrong-password"),
            401);
    assertThat(known).isEqualTo(unknown);
  }

  @Test
  void registrationValidatesEmailNameAndPassword() throws Exception {
    send(
        POST,
        "/api/v1/auth/register",
        null,
        Map.of("email", "bad", "displayName", " ", "password", "short"),
        400);
    send(
        POST,
        "/api/v1/auth/register",
        null,
        Map.of("email", "unicode@example.test", "displayName", "Test", "password", "é".repeat(40)),
        400);
  }

  @Test
  void protectedRoutesNeedBearerAuthentication() throws Exception {
    mvc.perform(get("/api/v1/organizations"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(401));
    send(GET, "/api/v1/auth/me", "not-a-jwt", null, 401);
  }

  @Test
  void tokenTamperingIsRejected() throws Exception {
    String[] parts = owner.token().split("\\.");
    char first = parts[2].charAt(0) == 'a' ? 'b' : 'a';
    String tampered = parts[0] + "." + parts[1] + "." + first + parts[2].substring(1);
    send(GET, "/api/v1/auth/me", tampered, null, 401);
  }

  @Test
  void expiredWrongIssuerWrongAudienceAndMissingClaimsAreRejected() throws Exception {
    Instant now = Instant.now();
    for (String invalid :
        List.of(
            signed(
                "https://ledgerflow.local",
                "ledgerflow-api",
                owner.id().toString(),
                now.minusSeconds(120),
                true),
            signed(
                "https://other.local",
                "ledgerflow-api",
                owner.id().toString(),
                now.plusSeconds(900),
                true),
            signed(
                "https://ledgerflow.local",
                "other-api",
                owner.id().toString(),
                now.plusSeconds(900),
                true),
            signed(
                "https://ledgerflow.local",
                "ledgerflow-api",
                "not-a-uuid",
                now.plusSeconds(900),
                true),
            signed(
                "https://ledgerflow.local",
                "ledgerflow-api",
                owner.id().toString(),
                now.plusSeconds(900),
                false))) {
      send(GET, "/api/v1/auth/me", invalid, null, 401);
    }
  }

  private String signed(
      String issuer, String audience, String subject, Instant expiry, boolean includeExpiry) {
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .audience(List.of(audience))
            .issuedAt(expiry.minusSeconds(900));
    if (includeExpiry) claims.expiresAt(expiry);
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
        .getTokenValue();
  }

  @Test
  void futureIssuedAtMissingAudienceAndMissingIssuedAtAreRejected() throws Exception {
    Instant now = Instant.now();
    var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
    for (var claims :
        List.of(
            JwtClaimsSet.builder()
                .issuer("https://ledgerflow.local")
                .subject(owner.id().toString())
                .audience(List.of("ledgerflow-api"))
                .issuedAt(now.plusSeconds(500))
                .expiresAt(now.plusSeconds(900))
                .build(),
            JwtClaimsSet.builder()
                .issuer("https://ledgerflow.local")
                .subject(owner.id().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build(),
            JwtClaimsSet.builder()
                .issuer("https://ledgerflow.local")
                .subject(owner.id().toString())
                .audience(List.of("ledgerflow-api"))
                .expiresAt(now.plusSeconds(900))
                .build())) {
      String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
      send(GET, "/api/v1/auth/me", token, null, 401);
    }
  }

  @Test
  void roleClaimsCannotGrantOrganizationPermissions() throws Exception {
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("https://ledgerflow.local")
            .subject(employee.id().toString())
            .audience(List.of("ledgerflow-api"))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(900))
            .claim("roles", List.of("OWNER"))
            .build();
    String token =
        encoder
            .encode(
                JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
            .getTokenValue();
    send(GET, base(org) + "/members", token, null, 403);
  }

  @Test
  void organizationIsCreatedWithOwnerAndListsOnlyMemberships() throws Exception {
    var response = send(GET, base(org), owner.token(), null, 200);
    assertThat(response.get("role").asText()).isEqualTo("OWNER");
    var outsider = register("outsider");
    send(GET, base(org), outsider.token(), null, 404);
    var list = send(GET, "/api/v1/organizations", outsider.token(), null, 200);
    assertThat(list.get("items").size()).isZero();
  }

  @Test
  void nonOwnersCannotManageMemberships() throws Exception {
    send(GET, base(org) + "/members", employee.token(), null, 403);
    send(
        POST,
        base(org) + "/members",
        accountant.token(),
        Map.of("email", accountant.email(), "role", "OWNER"),
        403);
    send(
        PUT,
        base(org) + "/members/" + employee.id(),
        employee.token(),
        Map.of("role", "OWNER"),
        403);
  }

  @Test
  void lastOwnerCannotBeRemovedOrDemoted() throws Exception {
    send(DELETE, base(org) + "/members/" + owner.id(), owner.token(), null, 409);
    send(PUT, base(org) + "/members/" + owner.id(), owner.token(), Map.of("role", "EMPLOYEE"), 409);
    assertThat(send(GET, base(org), owner.token(), null, 200).get("role").asText())
        .isEqualTo("OWNER");
  }

  @Test
  void membershipRemovalTakesEffectWithTheSameToken() throws Exception {
    send(GET, base(org), employee.token(), null, 200);
    send(DELETE, base(org) + "/members/" + employee.id(), owner.token(), null, 204);
    send(GET, base(org), employee.token(), null, 404);
  }

  @Test
  void roleChangeTakesEffectWithoutNewLogin() throws Exception {
    send(PUT, base(org) + "/members/" + employee.id(), owner.token(), Map.of("role", "OWNER"), 200);
    send(GET, base(org) + "/members", employee.token(), null, 200);
    send(
        PUT,
        base(org) + "/members/" + employee.id(),
        owner.token(),
        Map.of("role", "EMPLOYEE"),
        200);
    send(GET, base(org) + "/members", employee.token(), null, 403);
  }

  @Test
  void duplicateMembershipAndUnknownRoleAreRejected() throws Exception {
    send(
        POST,
        base(org) + "/members",
        owner.token(),
        Map.of("email", employee.email(), "role", "EMPLOYEE"),
        409);
    send(
        POST,
        base(org) + "/members",
        owner.token(),
        Map.of("email", employee.email(), "role", "SUPERADMIN"),
        400);
  }

  @Test
  void paginationIsBounded() throws Exception {
    send(GET, "/api/v1/organizations?size=101", owner.token(), null, 400);
    send(GET, base(org) + "/members?page=-1", owner.token(), null, 400);
  }

  @Test
  void ownerTransferKeepsAtLeastOneOwner() throws Exception {
    send(PUT, base(org) + "/members/" + employee.id(), owner.token(), Map.of("role", "OWNER"), 200);
    send(PUT, base(org) + "/members/" + owner.id(), owner.token(), Map.of("role", "EMPLOYEE"), 200);
    send(DELETE, base(org) + "/members/" + employee.id(), employee.token(), null, 409);
  }

  @Test
  void concurrentOwnerDemotionsCannotRemoveTheLastOwner() throws Exception {
    send(PUT, base(org) + "/members/" + employee.id(), owner.token(), Map.of("role", "OWNER"), 200);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var go = new java.util.concurrent.CountDownLatch(1);
    java.util.function.Function<TestUser, java.util.concurrent.Callable<Integer>> change =
        user ->
            () -> {
              ready.countDown();
              assertThat(go.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              var request =
                  put(base(org) + "/members/" + user.id())
                      .header("Authorization", "Bearer " + user.token())
                      .contentType("application/json")
                      .content(json.writeValueAsString(Map.of("role", "EMPLOYEE")));
              return mvc.perform(request).andReturn().getResponse().getStatus();
            };
    try {
      var first = pool.submit(change.apply(owner));
      var second = pool.submit(change.apply(employee));
      assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(
              List.of(
                  first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM ledgerflow.memberships WHERE organization_id = ? AND role = 'OWNER'",
                  Integer.class,
                  UUID.fromString(org)))
          .isEqualTo(1);
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }
}
