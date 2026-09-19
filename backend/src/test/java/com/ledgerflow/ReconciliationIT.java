package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerflow.banking.PlaidGateway;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@TestPropertySource(
    properties = {
      "ledgerflow.plaid.enabled=true",
      "ledgerflow.plaid.client-id=fixture-client",
      "ledgerflow.plaid.secret=fixture-secret",
      "ledgerflow.plaid.webhook-url=https://example.test/api/v1/webhooks/plaid",
      "ledgerflow.plaid.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    })
class ReconciliationIT extends IntegrationTestSupport {
  @MockitoBean PlaidGateway plaid;
  @Autowired JdbcTemplate jdbc;

  record Fixture(TestUser owner, String organization, String invoice, String connection) {}

  @BeforeEach
  void gateway() {
    reset(plaid);
    when(plaid.exchange(anyString()))
        .thenReturn(
            new PlaidGateway.Exchange(
                "access-sandbox-reconciliation", "item-" + UUID.randomUUID()));
  }

  Fixture fixture(String invoiceNumber, String amount, List<PlaidGateway.Transaction> transactions)
      throws Exception {
    var owner = register("reconciliation-owner");
    String organization = organization(owner);
    String customer =
        send(
                POST,
                base(organization) + "/customers",
                owner.token(),
                Map.of("name", "Synthetic customer", "email", "reconcile@example.test"),
                201)
            .get("id")
            .asText();
    String invoice =
        send(
                POST,
                base(organization) + "/invoices",
                owner.token(),
                Map.of(
                    "customerId",
                    customer,
                    "number",
                    invoiceNumber,
                    "currency",
                    "USD",
                    "dueDate",
                    LocalDate.now().plusDays(10).toString(),
                    "lines",
                    List.of(
                        Map.of(
                            "description",
                            "Delivered work",
                            "quantity",
                            1,
                            "unitPrice",
                            amount,
                            "taxRate",
                            "0"))),
                201)
            .get("id")
            .asText();
    send(
        POST,
        base(organization) + "/invoices/" + invoice + "/issue",
        owner.token(),
        Map.of("version", 0),
        200);
    String connection = connect(organization, owner);
    var account =
        new PlaidGateway.Account(
            "checking-1",
            "Checking",
            "Synthetic Checking",
            "0000",
            "depository",
            "checking",
            "USD",
            new BigDecimal("2000.00"),
            new BigDecimal("2000.00"));
    when(plaid.sync(anyString(), isNull()))
        .thenReturn(
            new PlaidGateway.SyncPage(
                List.of(account), transactions, List.of(), List.of(), "cursor-1", false));
    send(
        POST,
        base(organization) + "/banking/connections/" + connection + "/sync",
        owner.token(),
        null,
        200);
    return new Fixture(owner, organization, invoice, connection);
  }

  String connect(String organization, TestUser owner) throws Exception {
    var response =
        mvc.perform(
                MockMvcRequestBuilders.post(base(organization) + "/banking/connections")
                    .header("Authorization", "Bearer " + owner.token())
                    .header("Idempotency-Key", "reconcile-" + UUID.randomUUID())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("publicToken", "public-sandbox-" + UUID.randomUUID()))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(201);
    return json.readTree(response.getContentAsString()).get("id").asText();
  }

  PlaidGateway.Transaction bankTransaction(String id, String name, String amount, boolean pending) {
    return new PlaidGateway.Transaction(
        id,
        "checking-1",
        new BigDecimal(amount),
        "USD",
        LocalDate.now(),
        LocalDate.now(),
        name,
        null,
        pending,
        null);
  }

  JsonNode refresh(Fixture fixture) throws Exception {
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/refresh",
        fixture.owner().token(),
        null,
        200);
    return send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases?status=OPEN",
            fixture.owner().token(),
            null,
            200)
        .get(0);
  }

  @Test
  void exactReferenceMatchPostsCashAndSettlesInvoice() throws Exception {
    var fixture =
        fixture(
            "REC-100",
            "125.00",
            List.of(bankTransaction("bank-in-1", "ACH payment REC-100", "-125.00", false)));
    JsonNode value = refresh(fixture);
    assertThat(value.get("candidateCount").asInt()).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND invoice_id=?",
                Integer.class,
                UUID.fromString(fixture.invoice())))
        .isZero();
    JsonNode detail =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases/" + value.get("id").asText(),
            fixture.owner().token(),
            null,
            200);
    assertThat(detail.at("/candidates/0/score").decimalValue()).isEqualByComparingTo("1.0000");
    assertThat(detail.at("/candidates/0/rule").asText()).isEqualTo("REFERENCE_AND_EXACT_AMOUNT");
    JsonNode matched =
        send(
            POST,
            base(fixture.organization())
                + "/reconciliation/cases/"
                + value.get("id").asText()
                + "/match",
            fixture.owner().token(),
            Map.of("invoiceId", fixture.invoice(), "version", 0),
            200);
    assertThat(matched.at("/reconciliationCase/status").asText()).isEqualTo("MATCHED");
    assertThat(matched.at("/decisions/0/decision").asText()).isEqualTo("MATCHED");
    assertThat(
            send(
                    GET,
                    base(fixture.organization()) + "/invoices/" + fixture.invoice(),
                    fixture.owner().token(),
                    null,
                    200)
                .get("status")
                .asText())
        .isEqualTo("PAID");
    UUID journal =
        jdbc.queryForObject(
            "SELECT id FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND invoice_id=?",
            UUID.class,
            UUID.fromString(fixture.invoice()));
    assertThat(
            jdbc.queryForObject(
                "SELECT debit FROM ledgerflow.journal_entries WHERE journal_id=? AND account_code='CASH'",
                BigDecimal.class,
                journal))
        .isEqualByComparingTo("125.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT credit FROM ledgerflow.journal_entries WHERE journal_id=? AND account_code='RECEIVABLES'",
                BigDecimal.class,
                journal))
        .isEqualByComparingTo("125.00");
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    jdbc.update(
                        "DELETE FROM ledgerflow.reconciliation_decisions WHERE case_id=?",
                        UUID.fromString(value.get("id").asText()))))
        .isNotNull();
  }

  @Test
  void repeatedRefreshIsIdempotentAndOneInvoiceCannotBeSettledTwice() throws Exception {
    var fixture =
        fixture(
            "REC-150",
            "40.00",
            List.of(
                bankTransaction("bank-first", "REC-150 first", "-40.00", false),
                bankTransaction("bank-second", "REC-150 second", "-40.00", false)));
    JsonNode firstRefresh =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    JsonNode secondRefresh =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    assertThat(firstRefresh.get("newCases").asInt()).isEqualTo(2);
    assertThat(secondRefresh.get("newCases").asInt()).isZero();
    assertThat(secondRefresh.get("candidates").asInt()).isEqualTo(2);
    JsonNode cases =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases?status=OPEN",
            fixture.owner().token(),
            null,
            200);
    String first = cases.get(0).get("id").asText();
    String second = cases.get(1).get("id").asText();
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/cases/" + first + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", fixture.invoice(), "version", 0),
        200);
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/cases/" + second + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", fixture.invoice(), "version", 0),
        409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND invoice_id=?",
                Integer.class,
                UUID.fromString(fixture.invoice())))
        .isOne();
  }

  @Test
  void unmatchedPendingAndOutgoingTransactionsStayOutOfTheQueue() throws Exception {
    var fixture =
        fixture(
            "REC-200",
            "50.00",
            List.of(
                bankTransaction("outgoing", "Purchase", "50.00", false),
                bankTransaction("pending", "Pending incoming", "-50.00", true),
                bankTransaction("different", "Different incoming", "-51.00", false)));
    JsonNode result =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    assertThat(result.get("newCases").asInt()).isOne();
    assertThat(result.get("candidates").asInt()).isZero();
  }

  @Test
  void ignoreIsAuditedAndStaleRepeatIsRejected() throws Exception {
    var fixture =
        fixture(
            "REC-300",
            "75.00",
            List.of(bankTransaction("bank-ignore", "Unrelated deposit", "-75.00", false)));
    JsonNode value = refresh(fixture);
    String path =
        base(fixture.organization())
            + "/reconciliation/cases/"
            + value.get("id").asText()
            + "/ignore";
    send(POST, path, fixture.owner().token(), Map.of(), 400);
    JsonNode ignored = send(POST, path, fixture.owner().token(), Map.of("version", 0), 200);
    assertThat(ignored.at("/reconciliationCase/status").asText()).isEqualTo("IGNORED");
    assertThat(ignored.at("/decisions/0/invoiceId").isNull()).isTrue();
    send(POST, path, fixture.owner().token(), Map.of("version", 0), 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND organization_id=?",
                Integer.class,
                UUID.fromString(fixture.organization())))
        .isZero();
  }

  @Test
  void employeesCanReviewButCannotDecideAndOtherTenantsSeeNothing() throws Exception {
    var fixture =
        fixture(
            "REC-400", "90.00", List.of(bankTransaction("bank-role", "REC-400", "-90.00", false)));
    JsonNode value = refresh(fixture);
    var employee = register("reconciliation-employee");
    addMember(fixture.organization(), fixture.owner(), employee, "EMPLOYEE");
    send(GET, base(fixture.organization()) + "/reconciliation/cases", employee.token(), null, 200);
    send(
        POST,
        base(fixture.organization())
            + "/reconciliation/cases/"
            + value.get("id").asText()
            + "/ignore",
        employee.token(),
        Map.of("version", 0),
        403);
    var other = register("reconciliation-other");
    String otherOrganization = organization(other);
    send(
        GET,
        base(otherOrganization) + "/reconciliation/cases/" + value.get("id").asText(),
        other.token(),
        null,
        404);
  }

  @Test
  void concurrentReviewersCreateOneDecisionAndOneJournal() throws Exception {
    var fixture =
        fixture(
            "REC-500",
            "110.00",
            List.of(bankTransaction("bank-concurrent", "REC-500", "-110.00", false)));
    JsonNode value = refresh(fixture);
    String path =
        base(fixture.organization())
            + "/reconciliation/cases/"
            + value.get("id").asText()
            + "/match";
    String body = json.writeValueAsString(Map.of("invoiceId", fixture.invoice(), "version", 0));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Integer> request =
          () -> {
            ready.countDown();
            start.await();
            return mvc.perform(
                    MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + fixture.owner().token())
                        .contentType("application/json")
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      Future<Integer> first = executor.submit(request), second = executor.submit(request);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    }
    UUID caseId = UUID.fromString(value.get("id").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.reconciliation_decisions WHERE case_id=?",
                Integer.class,
                caseId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND invoice_id=?",
                Integer.class,
                UUID.fromString(fixture.invoice())))
        .isOne();
  }
}
