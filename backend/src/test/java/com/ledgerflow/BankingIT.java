package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.*;

import com.ledgerflow.banking.PlaidGateway;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "ledgerflow.plaid.enabled=true",
      "ledgerflow.plaid.client-id=fixture-client",
      "ledgerflow.plaid.secret=sandbox-fixture",
      "ledgerflow.plaid.webhook-url=https://example.test/api/v1/webhooks/plaid",
      "ledgerflow.plaid.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    })
class BankingIT extends IntegrationTestSupport {
  @MockitoBean PlaidGateway plaid;
  @Autowired JdbcTemplate jdbc;
  ECKey webhookKey;

  record Fixture(TestUser owner, String organization) {}

  @BeforeEach
  void gateway() throws Exception {
    reset(plaid);
    webhookKey = new ECKeyGenerator(Curve.P_256).keyID("fixture-key").generate();
    when(plaid.verificationKey("fixture-key"))
        .thenReturn(
            new PlaidGateway.VerificationKey(
                "ES256",
                "P-256",
                "EC",
                "sig",
                "fixture-key",
                webhookKey.getX().toString(),
                webhookKey.getY().toString(),
                Instant.now().minusSeconds(60).getEpochSecond(),
                null));
    when(plaid.createLinkToken(any(), anyString())).thenReturn("link-sandbox-fixture");
    when(plaid.exchange(anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new PlaidGateway.Exchange(
                  "access-sandbox-very-secret", "item-" + UUID.randomUUID());
            });
  }

  Fixture fixture() throws Exception {
    var owner = register("bank-owner");
    return new Fixture(owner, organization(owner));
  }

  String connect(Fixture fixture, String key) throws Exception {
    var raw =
        mvc.perform(
                MockMvcRequestBuilders.post(base(fixture.organization()) + "/banking/connections")
                    .header("Authorization", "Bearer " + fixture.owner().token())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("publicToken", "public-sandbox-fixture-" + key))))
            .andReturn()
            .getResponse();
    assertThat(raw.getStatus()).isEqualTo(201);
    var response = json.readTree(raw.getContentAsString());
    assertThat(response.has("accessToken")).isFalse();
    assertThat(response.has("cursor")).isFalse();
    return response.get("id").asText();
  }

  PlaidGateway.Account account() {
    return new PlaidGateway.Account(
        "account-1",
        "Plaid Checking",
        "Plaid Gold Checking",
        "0000",
        "depository",
        "checking",
        "USD",
        new BigDecimal("1250.25"),
        new BigDecimal("1200.25"));
  }

  PlaidGateway.Transaction transaction(String id, String name, String amount) {
    return new PlaidGateway.Transaction(
        id,
        "account-1",
        new BigDecimal(amount),
        "USD",
        LocalDate.of(2026, 9, 18),
        LocalDate.of(2026, 9, 17),
        name,
        "Sandbox shop",
        false,
        null);
  }

  @Test
  void linksExchangesEncryptsAndNeverReturnsSecrets() throws Exception {
    var f = fixture();
    var link =
        send(POST, base(f.organization()) + "/banking/link-token", f.owner().token(), null, 200);
    assertThat(link.get("linkToken").asText()).isEqualTo("link-sandbox-fixture");
    String id = connect(f, "connect-once");
    var replayId = connect(f, "connect-once");
    assertThat(replayId).isEqualTo(id);
    verify(plaid, times(1)).exchange(anyString());

    byte[] encrypted =
        jdbc.queryForObject(
            "SELECT access_token_cipher FROM ledgerflow.bank_connections WHERE id=?",
            byte[].class,
            UUID.fromString(id));
    assertThat(new String(encrypted)).doesNotContain("access-sandbox-very-secret");
    assertThat(
            send(GET, base(f.organization()) + "/banking/connections", f.owner().token(), null, 200)
                .toString())
        .doesNotContain("access-sandbox", "idempotency", "publicToken", "cursor");
  }

  @Test
  void syncAppliesAddsChangesAndRemovalsWithAppendOnlyHistory() throws Exception {
    var f = fixture();
    String connection = connect(f, "sync");
    when(plaid.sync(anyString(), isNull()))
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account()),
                List.of(transaction("tx-1", "Coffee", "4.25")),
                List.of(),
                List.of(),
                "cursor-1",
                false));
    send(
        POST,
        base(f.organization()) + "/banking/connections/" + connection + "/sync",
        f.owner().token(),
        null,
        200);
    var accounts =
        send(
            GET,
            base(f.organization()) + "/banking/connections/" + connection + "/accounts",
            f.owner().token(),
            null,
            200);
    assertThat(accounts.get(0).get("currentBalance").decimalValue())
        .isEqualByComparingTo("1250.25");
    assertThat(
            send(
                    GET,
                    base(f.organization()) + "/banking/transactions",
                    f.owner().token(),
                    null,
                    200)
                .get(0)
                .get("name")
                .asText())
        .isEqualTo("Coffee");

    when(plaid.sync(anyString(), eq("cursor-1")))
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account()),
                List.of(),
                List.of(transaction("tx-1", "Coffee corrected", "4.50")),
                List.of(),
                "cursor-2",
                false));
    send(
        POST,
        base(f.organization()) + "/banking/connections/" + connection + "/sync",
        f.owner().token(),
        null,
        200);
    when(plaid.sync(anyString(), eq("cursor-2")))
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account()), List.of(), List.of(), List.of("tx-1"), "cursor-3", false));
    send(
        POST,
        base(f.organization()) + "/banking/connections/" + connection + "/sync",
        f.owner().token(),
        null,
        200);
    assertThat(
            send(
                GET,
                base(f.organization()) + "/banking/transactions",
                f.owner().token(),
                null,
                200))
        .isEmpty();
    assertThat(
            send(
                    GET,
                    base(f.organization()) + "/banking/transactions?includeRemoved=true",
                    f.owner().token(),
                    null,
                    200)
                .get(0)
                .get("removed")
                .asBoolean())
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.bank_transaction_changes WHERE provider_transaction_id='tx-1'",
                Integer.class))
        .isEqualTo(3);
    assertThatThrownBySql(
        "UPDATE ledgerflow.bank_transaction_changes SET change_type='ADDED' WHERE provider_transaction_id='tx-1'");
  }

  @Test
  void paginationRestartsAfterPlaidMutationAndCommitsOneBatch() throws Exception {
    var f = fixture();
    String connection = connect(f, "pagination");
    when(plaid.sync(anyString(), isNull()))
        .thenThrow(new PlaidGateway.MutationDuringPagination())
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account()),
                List.of(transaction("tx-restarted", "Restarted", "10.00")),
                List.of(),
                List.of(),
                "done",
                false));
    send(
        POST,
        base(f.organization()) + "/banking/connections/" + connection + "/sync",
        f.owner().token(),
        null,
        200);
    verify(plaid, times(2)).sync(anyString(), isNull());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.bank_transaction_changes WHERE provider_transaction_id='tx-restarted'",
                Integer.class))
        .isOne();
  }

  @Test
  void rolesAndTenantBoundariesAreEnforced() throws Exception {
    var f = fixture();
    var employee = register("bank-employee");
    addMember(f.organization(), f.owner(), employee, "EMPLOYEE");
    send(POST, base(f.organization()) + "/banking/link-token", employee.token(), null, 403);
    String connection = connect(f, "roles");
    send(
        GET,
        base(f.organization()) + "/banking/connections/" + connection,
        employee.token(),
        null,
        200);
    send(
        POST,
        base(f.organization()) + "/banking/connections/" + connection + "/sync",
        employee.token(),
        null,
        403);
    var other = register("other-bank");
    String otherOrg = organization(other);
    send(GET, base(otherOrg) + "/banking/connections/" + connection, other.token(), null, 404);
  }

  @Test
  void verifiedWebhookRunsSyncAndChangedBodyIsRejected() throws Exception {
    var f = fixture();
    String connection = connect(f, "webhook");
    String item =
        jdbc.queryForObject(
            "SELECT provider_item_id FROM ledgerflow.bank_connections WHERE id=?",
            String.class,
            UUID.fromString(connection));
    when(plaid.sync(anyString(), isNull()))
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account()),
                List.of(transaction("tx-webhook", "Webhook import", "12.34")),
                List.of(),
                List.of(),
                "webhook-cursor",
                false));
    String body =
        json.writeValueAsString(
            Map.of(
                "environment",
                "sandbox",
                "webhook_type",
                "TRANSACTIONS",
                "webhook_code",
                "SYNC_UPDATES_AVAILABLE",
                "item_id",
                item));
    String signature = sign(body);
    var valid =
        MockMvcRequestBuilders.post("/api/v1/webhooks/plaid")
            .contentType("application/json")
            .header("Plaid-Verification", signature)
            .content(body);
    assertThat(mvc.perform(valid).andReturn().getResponse().getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.bank_transactions WHERE provider_transaction_id='tx-webhook'",
                Integer.class))
        .isOne();
    var changed =
        MockMvcRequestBuilders.post("/api/v1/webhooks/plaid")
            .contentType("application/json")
            .header("Plaid-Verification", signature)
            .content(body + " ");
    assertThat(mvc.perform(changed).andReturn().getResponse().getStatus()).isEqualTo(400);
  }

  private String sign(String body) throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    var claims =
        new JWTClaimsSet.Builder()
            .issueTime(Date.from(Instant.now()))
            .claim("request_body_sha256", hash)
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("fixture-key").build(), claims);
    jwt.sign(new ECDSASigner(webhookKey));
    return jwt.serialize();
  }

  private void assertThatThrownBySql(String sql) {
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(sql))).isNotNull();
  }
}
