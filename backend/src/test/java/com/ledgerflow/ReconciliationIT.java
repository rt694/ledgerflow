package com.ledgerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerflow.banking.PlaidGateway;
import com.ledgerflow.payment.StripeGateway;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
      "ledgerflow.plaid.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "ledgerflow.stripe.enabled=true",
      "ledgerflow.stripe.secret-key=sk_test_fixture",
      "ledgerflow.stripe.webhook-secret=whsec_fixture"
    })
class ReconciliationIT extends IntegrationTestSupport {
  @MockitoBean PlaidGateway plaid;
  @MockitoBean StripeGateway stripe;
  @Autowired JdbcTemplate jdbc;

  record Fixture(TestUser owner, String organization, String invoice, String connection) {}

  @BeforeEach
  void gateway() {
    reset(plaid, stripe);
    when(plaid.exchange(anyString()))
        .thenReturn(
            new PlaidGateway.Exchange(
                "access-sandbox-reconciliation", "item-" + UUID.randomUUID()));
  }

  Fixture fixture(String invoiceNumber, String amount, List<PlaidGateway.Transaction> transactions)
      throws Exception {
    var owner = register("reconciliation-owner");
    String organization = organization(owner);
    String invoice = invoice(organization, owner, invoiceNumber, amount);
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

  String invoice(String organization, TestUser owner, String invoiceNumber, String amount)
      throws Exception {
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
    return invoice;
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
        LocalDate.now(ZoneOffset.UTC),
        LocalDate.now(ZoneOffset.UTC),
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

  JsonNode paidPayout(Fixture fixture, String payoutProviderId, LocalDate arrivalDate)
      throws Exception {
    when(stripe.create(any(), any(), any(), eq(10000L)))
        .thenAnswer(
            call -> {
              UUID paymentId = call.getArgument(0);
              UUID organizationId = call.getArgument(1);
              UUID invoiceId = call.getArgument(2);
              return new StripeGateway.Intent(
                  "pi_" + paymentId,
                  10000,
                  "usd",
                  "requires_payment_method",
                  false,
                  "synthetic_client_secret",
                  Map.of(
                      "ledgerflow_payment_id",
                      paymentId.toString(),
                      "ledgerflow_organization_id",
                      organizationId.toString(),
                      "ledgerflow_invoice_id",
                      invoiceId.toString()));
            });
    var paymentResponse =
        mvc.perform(
                MockMvcRequestBuilders.post(base(fixture.organization()) + "/payments")
                    .header("Authorization", "Bearer " + fixture.owner().token())
                    .header("Idempotency-Key", "payout-bank-match")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("invoiceId", fixture.invoice()))))
            .andReturn()
            .getResponse();
    assertThat(paymentResponse.getStatus()).isEqualTo(200);
    JsonNode payment = json.readTree(paymentResponse.getContentAsString());
    String intentId = payment.get("providerId").asText();
    UUID paymentId = UUID.fromString(payment.get("paymentId").asText());
    var succeeded =
        new StripeGateway.Intent(
            intentId,
            10000,
            "usd",
            "succeeded",
            false,
            "synthetic_client_secret",
            Map.of(
                "ledgerflow_payment_id",
                paymentId.toString(),
                "ledgerflow_organization_id",
                fixture.organization(),
                "ledgerflow_invoice_id",
                fixture.invoice()));
    when(stripe.retrieve(intentId)).thenReturn(succeeded);
    String event =
        json.writeValueAsString(
            Map.of(
                "id",
                "evt_" + UUID.randomUUID(),
                "object",
                "event",
                "type",
                "payment_intent.succeeded",
                "livemode",
                false,
                "data",
                Map.of("object", Map.of("id", intentId))));
    long timestamp = Instant.now().getEpochSecond();
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_fixture".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        "t="
            + timestamp
            + ",v1="
            + HexFormat.of()
                .formatHex(mac.doFinal((timestamp + "." + event).getBytes(StandardCharsets.UTF_8)));
    assertThat(
            mvc.perform(
                    MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
                        .header("Stripe-Signature", signature)
                        .contentType("application/json")
                        .content(event))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    when(stripe.retrievePayout(payoutProviderId))
        .thenReturn(
            new StripeGateway.Payout(
                payoutProviderId,
                9700,
                "usd",
                "paid",
                false,
                arrivalDate,
                List.of(
                    new StripeGateway.PayoutLine(
                        "txn_payout_bank_match", "charge", intentId, 10000, 300, 9700, "usd"))));
    return send(
        POST,
        base(fixture.organization()) + "/stripe-payouts/import",
        fixture.owner().token(),
        Map.of("providerPayoutId", payoutProviderId),
        200);
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
    assertThat(matched.at("/reconciliationCase/matchedAmount").decimalValue())
        .isEqualByComparingTo("125.00");
    assertThat(matched.at("/decisions/0/decision").asText()).isEqualTo("MATCHED");
    assertThat(matched.at("/decisions/0/amount").decimalValue()).isEqualByComparingTo("125.00");
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
  void reviewedPayoutDepositMovesTransitIntoCashOnce() throws Exception {
    LocalDate bankDate = LocalDate.now(ZoneOffset.UTC);
    var fixture =
        fixture(
            "REC-PAYOUT",
            "100.00",
            List.of(
                new PlaidGateway.Transaction(
                    "bank-payout-1",
                    "checking-1",
                    new BigDecimal("-97.00"),
                    "USD",
                    bankDate,
                    bankDate,
                    "STRIPE PAYOUT po_bankmatch",
                    null,
                    false,
                    null)));
    JsonNode payout = paidPayout(fixture, "po_bankmatch", bankDate);

    JsonNode summary =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    assertThat(summary.get("invoiceCandidates").asInt()).isZero();
    assertThat(summary.get("payoutCandidates").asInt()).isOne();
    JsonNode reconciliationCase =
        send(
                GET,
                base(fixture.organization()) + "/reconciliation/cases?status=OPEN",
                fixture.owner().token(),
                null,
                200)
            .get(0);
    assertThat(reconciliationCase.get("candidateCount").asInt()).isOne();
    assertThat(reconciliationCase.get("payoutCandidateCount").asInt()).isOne();
    assertThat(reconciliationCase.get("reviewState").asText()).isEqualTo("SINGLE_CANDIDATE");
    String caseId = reconciliationCase.get("id").asText();
    JsonNode detail =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases/" + caseId,
            fixture.owner().token(),
            null,
            200);
    assertThat(detail.get("candidates")).isEmpty();
    assertThat(detail.get("payoutCandidates")).hasSize(1);
    assertThat(detail.at("/payoutCandidates/0/payoutId").asText())
        .isEqualTo(payout.get("id").asText());
    assertThat(detail.at("/payoutCandidates/0/rule").asText())
        .isEqualTo("PAYOUT_REFERENCE_AND_AMOUNT");

    var employee = register("payout-review-employee");
    addMember(fixture.organization(), fixture.owner(), employee, "EMPLOYEE");
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/cases/" + caseId + "/match-payout",
        employee.token(),
        Map.of("payoutId", payout.get("id").asText(), "version", 0),
        403);
    JsonNode matched =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/cases/" + caseId + "/match-payout",
            fixture.owner().token(),
            Map.of("payoutId", payout.get("id").asText(), "version", 0),
            200);
    assertThat(matched.at("/reconciliationCase/status").asText()).isEqualTo("MATCHED");
    assertThat(matched.at("/reconciliationCase/matchedInvoiceId").isNull()).isTrue();
    assertThat(matched.at("/reconciliationCase/matchedPayoutId").asText())
        .isEqualTo(payout.get("id").asText());
    assertThat(matched.at("/reconciliationCase/matchedAmount").decimalValue())
        .isEqualByComparingTo("97.00");
    assertThat(matched.at("/decisions/0/payoutId").asText()).isEqualTo(payout.get("id").asText());
    JsonNode depositedPayout =
        send(
            GET,
            base(fixture.organization()) + "/stripe-payouts/" + payout.get("id").asText(),
            fixture.owner().token(),
            null,
            200);
    assertThat(depositedPayout.get("bankTransactionId").isNull()).isFalse();
    assertThat(depositedPayout.get("depositedAt").isNull()).isFalse();

    UUID organizationId = UUID.fromString(fixture.organization());
    assertThat(accountBalance(organizationId, "PAYOUTS_IN_TRANSIT")).isEqualByComparingTo("0.00");
    assertThat(accountBalance(organizationId, "CASH")).isEqualByComparingTo("97.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=? AND payout_id=? AND operation='PAYOUT_DEPOSIT' AND invoice_id IS NULL",
                Integer.class,
                organizationId,
                UUID.fromString(payout.get("id").asText())))
        .isOne();
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/cases/" + caseId + "/match-payout",
        fixture.owner().token(),
        Map.of("payoutId", payout.get("id").asText(), "version", 0),
        409);
  }

  @Test
  void referencedPartialPaymentsReduceTheBalanceAndFinalPaymentSettlesInvoice() throws Exception {
    var fixture =
        fixture(
            "REC-PART",
            "100.00",
            List.of(
                bankTransaction("bank-part-1", "First payment REC-PART", "-40.00", false),
                bankTransaction("bank-part-2", "Final payment REC-PART", "-60.00", false)));
    send(
        POST,
        base(fixture.organization()) + "/reconciliation/refresh",
        fixture.owner().token(),
        null,
        200);
    JsonNode open =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases?status=OPEN",
            fixture.owner().token(),
            null,
            200);
    JsonNode first = findCase(open, "First payment REC-PART");
    JsonNode firstDetail =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases/" + first.get("id").asText(),
            fixture.owner().token(),
            null,
            200);
    assertThat(firstDetail.at("/candidates/0/rule").asText()).isEqualTo("REFERENCE_PARTIAL_AMOUNT");
    assertThat(firstDetail.at("/candidates/0/outstandingBalance").decimalValue())
        .isEqualByComparingTo("100.00");
    send(
        POST,
        base(fixture.organization())
            + "/reconciliation/cases/"
            + first.get("id").asText()
            + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", fixture.invoice(), "version", 0),
        200);
    assertThat(invoiceStatus(fixture)).isEqualTo("ISSUED");
    assertThat(receivableBalance(fixture.invoice())).isEqualByComparingTo("60.00");
    var cardAttempt =
        mvc.perform(
                MockMvcRequestBuilders.post(base(fixture.organization()) + "/payments")
                    .header("Authorization", "Bearer " + fixture.owner().token())
                    .header("Idempotency-Key", "card-after-bank")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("invoiceId", fixture.invoice()))))
            .andReturn()
            .getResponse();
    assertThat(cardAttempt.getStatus()).isEqualTo(409);
    verifyNoInteractions(stripe);

    send(
        POST,
        base(fixture.organization()) + "/reconciliation/refresh",
        fixture.owner().token(),
        null,
        200);
    open =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases?status=OPEN",
            fixture.owner().token(),
            null,
            200);
    JsonNode second = findCase(open, "Final payment REC-PART");
    JsonNode secondDetail =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases/" + second.get("id").asText(),
            fixture.owner().token(),
            null,
            200);
    assertThat(secondDetail.at("/candidates/0/rule").asText())
        .isEqualTo("REFERENCE_AND_EXACT_AMOUNT");
    send(
        POST,
        base(fixture.organization())
            + "/reconciliation/cases/"
            + second.get("id").asText()
            + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", fixture.invoice(), "version", 0),
        200);
    assertThat(invoiceStatus(fixture)).isEqualTo("PAID");
    assertThat(receivableBalance(fixture.invoice())).isEqualByComparingTo("0.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='BANK_PAYMENT' AND invoice_id=?",
                Integer.class,
                UUID.fromString(fixture.invoice())))
        .isEqualTo(2);
  }

  @Test
  void multipleExactMatchesAreMarkedForReviewAndRequireAnExplicitChoice() throws Exception {
    var fixture =
        fixture(
            "REC-AMB-A",
            "80.00",
            List.of(bankTransaction("bank-ambiguous", "Customer transfer", "-80.00", false)));
    String otherInvoice = invoice(fixture.organization(), fixture.owner(), "REC-AMB-B", "80.00");
    JsonNode value = refresh(fixture);
    assertThat(value.get("candidateCount").asInt()).isEqualTo(2);
    assertThat(value.get("reviewState").asText()).isEqualTo("MULTIPLE_CANDIDATES");
    JsonNode detail =
        send(
            GET,
            base(fixture.organization()) + "/reconciliation/cases/" + value.get("id").asText(),
            fixture.owner().token(),
            null,
            200);
    assertThat(detail.get("candidates")).hasSize(2);
    send(
        POST,
        base(fixture.organization())
            + "/reconciliation/cases/"
            + value.get("id").asText()
            + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", otherInvoice, "version", 0),
        200);
    assertThat(invoiceStatus(fixture.organization(), fixture.owner(), otherInvoice))
        .isEqualTo("PAID");
    assertThat(invoiceStatus(fixture)).isEqualTo("ISSUED");
  }

  @Test
  void activeCardAttemptBlocksAStaleBankSuggestion() throws Exception {
    var fixture =
        fixture(
            "REC-CARD",
            "70.00",
            List.of(bankTransaction("bank-card", "REC-CARD", "-70.00", false)));
    JsonNode value = refresh(fixture);
    jdbc.update(
        "INSERT INTO ledgerflow.payments(id,organization_id,invoice_id,currency,amount,idempotency_hash,state,created_at) VALUES (?,?,?,'USD',70.00,?,'PENDING',now())",
        UUID.randomUUID(),
        UUID.fromString(fixture.organization()),
        UUID.fromString(fixture.invoice()),
        UUID.randomUUID().toString().replace("-", ""));
    send(
        POST,
        base(fixture.organization())
            + "/reconciliation/cases/"
            + value.get("id").asText()
            + "/match",
        fixture.owner().token(),
        Map.of("invoiceId", fixture.invoice(), "version", 0),
        409);
    JsonNode refreshed =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    assertThat(refreshed.get("candidates").asInt()).isZero();
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
  void unmatchedPartialOverpaymentPendingAndOutgoingTransactionsHaveNoCandidates()
      throws Exception {
    var fixture =
        fixture(
            "REC-200",
            "50.00",
            List.of(
                bankTransaction("outgoing", "Purchase", "50.00", false),
                bankTransaction("pending", "Pending incoming", "-50.00", true),
                bankTransaction("partial", "Unlabelled partial", "-25.00", false),
                bankTransaction("sub-cent", "REC-200 sub-cent", "-25.001", false),
                bankTransaction("overpayment", "REC-200 too much", "-51.00", false)));
    JsonNode result =
        send(
            POST,
            base(fixture.organization()) + "/reconciliation/refresh",
            fixture.owner().token(),
            null,
            200);
    assertThat(result.get("newCases").asInt()).isEqualTo(3);
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

  JsonNode findCase(JsonNode cases, String description) {
    for (JsonNode value : cases) {
      if (description.equals(value.get("bankDescription").asText())) return value;
    }
    throw new AssertionError("Missing reconciliation case for " + description);
  }

  String invoiceStatus(Fixture fixture) throws Exception {
    return invoiceStatus(fixture.organization(), fixture.owner(), fixture.invoice());
  }

  String invoiceStatus(String organization, TestUser owner, String invoice) throws Exception {
    return send(GET, base(organization) + "/invoices/" + invoice, owner.token(), null, 200)
        .get("status")
        .asText();
  }

  BigDecimal receivableBalance(String invoice) {
    return jdbc.queryForObject(
        "SELECT sum(e.debit-e.credit) FROM ledgerflow.journal_entries e JOIN ledgerflow.journal_transactions j ON j.id=e.journal_id WHERE j.invoice_id=? AND e.account_code='RECEIVABLES'",
        BigDecimal.class,
        UUID.fromString(invoice));
  }

  BigDecimal accountBalance(UUID organizationId, String account) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(debit-credit),0) FROM ledgerflow.journal_entries WHERE organization_id=? AND account_code=?",
        BigDecimal.class,
        organizationId,
        account);
  }
}
