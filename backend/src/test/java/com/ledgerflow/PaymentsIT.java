package com.ledgerflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(
    properties = {
      "ledgerflow.stripe.enabled=true",
      "ledgerflow.stripe.secret-key=sk_test_fixture",
      "ledgerflow.stripe.webhook-secret=whsec_fixture"
    })
class PaymentsIT extends IntegrationTestSupport {
  @MockitoBean StripeGateway stripe;
  @Autowired JdbcTemplate jdbc;
  Map<String, StripeGateway.Intent> intents;

  @BeforeEach
  void gateway() {
    intents = new ConcurrentHashMap<>();
    when(stripe.create(any(), any(), any(), anyLong()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID id = call.getArgument(0),
                  org = call.getArgument(1),
                  invoice = call.getArgument(2);
              long cents = call.getArgument(3);
              return intents.computeIfAbsent(
                  "pi_" + id,
                  k ->
                      new StripeGateway.Intent(
                          k,
                          cents,
                          "usd",
                          "requires_payment_method",
                          false,
                          "synthetic_client_secret",
                          Map.of(
                              "ledgerflow_payment_id",
                              id.toString(),
                              "ledgerflow_organization_id",
                              org.toString(),
                              "ledgerflow_invoice_id",
                              invoice.toString())));
            });
    when(stripe.retrieve(anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return intents.get(call.getArgument(0));
            });
  }

  record Fixture(TestUser owner, String org, String invoice) {}

  Fixture fixture() throws Exception {
    return fixture("100.00");
  }

  Fixture fixture(String price) throws Exception {
    var owner = register("payment");
    var org = organization(owner);
    return new Fixture(owner, org, invoice(owner, org, "PAY-1", price));
  }

  String invoice(TestUser owner, String org, String number, String price) throws Exception {
    var customer =
        send(
                POST,
                base(org) + "/customers",
                owner.token(),
                Map.of("name", "Synthetic", "email", "customer@example.test"),
                201)
            .get("id")
            .asText();
    var invoice =
        send(
                POST,
                base(org) + "/invoices",
                owner.token(),
                Map.of(
                    "customerId",
                    customer,
                    "number",
                    number,
                    "currency",
                    "USD",
                    "dueDate",
                    LocalDate.now().plusDays(30).toString(),
                    "lines",
                    List.of(
                        Map.of(
                            "description",
                            "Delivered",
                            "quantity",
                            1,
                            "unitPrice",
                            price,
                            "taxRate",
                            "0"))),
                201)
            .get("id")
            .asText();
    send(
        POST,
        base(org) + "/invoices/" + invoice + "/issue",
        owner.token(),
        Map.of("version", 0),
        200);
    return invoice;
  }

  JsonNode create(Fixture f, String key, int expected) throws Exception {
    var response =
        mvc.perform(
                MockMvcRequestBuilders.post(base(f.org()) + "/payments")
                    .header("Authorization", "Bearer " + f.owner().token())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("invoiceId", f.invoice()))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(expected);
    if (expected == 200) assertThat(response.getHeader("Cache-Control")).contains("no-store");
    return json.readTree(response.getContentAsString());
  }

  StripeGateway.Intent status(JsonNode payment, String status) {
    var old = intents.get(payment.get("providerId").asText());
    var updated =
        new StripeGateway.Intent(
            old.id(),
            old.amount(),
            old.currency(),
            status,
            false,
            old.clientSecret(),
            old.metadata());
    intents.put(old.id(), updated);
    return updated;
  }

  String payload(String id, String type, String object, boolean live) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "id",
            id,
            "object",
            "event",
            "type",
            type,
            "livemode",
            live,
            "data",
            Map.of("object", Map.of("id", object))));
  }

  String sign(String payload, long timestamp) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_fixture".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "t="
        + timestamp
        + ",v1="
        + HexFormat.of()
            .formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
  }

  void webhook(String id, String type, String object, int expected) throws Exception {
    var body = payload(id, type, object, false);
    raw(body, sign(body, Instant.now().getEpochSecond()), expected);
  }

  void raw(String body, String signature, int expected) throws Exception {
    var request =
        MockMvcRequestBuilders.post("/api/v1/webhooks/stripe")
            .contentType("application/json")
            .content(body);
    if (signature != null) request.header("Stripe-Signature", signature);
    assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(expected);
  }

  String invoiceStatus(Fixture f) throws Exception {
    return send(GET, base(f.org()) + "/invoices/" + f.invoice(), f.owner().token(), null, 200)
        .get("status")
        .asText();
  }

  int postings(JsonNode payment) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ledgerflow.journal_transactions WHERE payment_id=?",
        Integer.class,
        UUID.fromString(payment.get("paymentId").asText()));
  }

  JsonNode succeed(Fixture fixture, String key) throws Exception {
    JsonNode payment = create(fixture, key, 200);
    StripeGateway.Intent intent = status(payment, "succeeded");
    webhook("evt_" + UUID.randomUUID(), "payment_intent.succeeded", intent.id(), 200);
    return payment;
  }

  JsonNode importPayout(Fixture fixture, String providerId, int expected) throws Exception {
    return send(
        POST,
        base(fixture.org()) + "/stripe-payouts/import",
        fixture.owner().token(),
        Map.of("providerPayoutId", providerId),
        expected);
  }

  @Test
  void successPostsOnceAndLateFailureCannotRegressIt() throws Exception {
    var f = fixture();
    var p = create(f, "success", 200);
    status(p, "succeeded");
    webhook(
        "evt_" + UUID.randomUUID(), "payment_intent.succeeded", p.get("providerId").asText(), 200);
    String duplicate = "evt_" + UUID.randomUUID();
    webhook(duplicate, "payment_intent.succeeded", p.get("providerId").asText(), 200);
    webhook(duplicate, "payment_intent.succeeded", p.get("providerId").asText(), 200);
    assertThat(postings(p)).isEqualTo(1);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
    send(
        POST,
        base(f.org()) + "/payments/" + p.get("paymentId").asText() + "/cancel",
        f.owner().token(),
        null,
        409);
    verify(stripe, never()).cancel(anyString(), anyString());
    status(p, "requires_payment_method");
    webhook(
        "evt_" + UUID.randomUUID(),
        "payment_intent.payment_failed",
        p.get("providerId").asText(),
        200);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
    var read =
        send(
            GET,
            base(f.org()) + "/payments/" + p.get("paymentId").asText(),
            f.owner().token(),
            null,
            200);
    assertThat(read.get("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(read.has("clientSecret")).isFalse();
    assertThat(read.has("keyHash")).isFalse();
  }

  @Test
  void badSignaturesOldFutureAndLiveEventsAreRejected() throws Exception {
    var body = payload("evt_bad", "payment_intent.succeeded", "pi_bad", false);
    long now = Instant.now().getEpochSecond();
    raw(body, null, 400);
    raw(body, "t=" + now + ",v1=bad", 400);
    raw(body, sign(body, now - 600), 400);
    raw(body, sign(body, now + 600), 400);
    raw(body + " ", sign(body, now), 400);
    var live = payload("evt_live", "payment_intent.succeeded", "pi_bad", true);
    raw(live, sign(live, now), 400);
    verify(stripe, never()).retrieve(anyString());
  }

  @Test
  void mismatchedAmountsCurrenciesAndTenantMetadataRollBack() throws Exception {
    var f = fixture();
    var p = create(f, "mismatch", 200);
    var original = status(p, "succeeded");
    for (var invalid :
        List.of(
            new StripeGateway.Intent(
                original.id(), 9999, "usd", "succeeded", false, null, original.metadata()),
            new StripeGateway.Intent(
                original.id(), 10000, "eur", "succeeded", false, null, original.metadata()),
            new StripeGateway.Intent(
                original.id(),
                10000,
                "usd",
                "succeeded",
                false,
                null,
                Map.of(
                    "ledgerflow_payment_id",
                    p.get("paymentId").asText(),
                    "ledgerflow_organization_id",
                    UUID.randomUUID().toString(),
                    "ledgerflow_invoice_id",
                    f.invoice())))) {
      intents.put(original.id(), invalid);
      webhook("evt_" + UUID.randomUUID(), "payment_intent.succeeded", original.id(), 400);
    }
    assertThat(postings(p)).isZero();
    assertThat(invoiceStatus(f)).isEqualTo("ISSUED");
  }

  @Test
  void rolesScopeAndRequiredIdempotencyAreEnforced() throws Exception {
    var f = fixture();
    var other = register("other");
    addMember(f.org(), f.owner(), other, "EMPLOYEE");
    send(POST, base(f.org()) + "/payments", other.token(), Map.of("invoiceId", f.invoice()), 400);
    var denied =
        mvc.perform(
                MockMvcRequestBuilders.post(base(f.org()) + "/payments")
                    .header("Authorization", "Bearer " + other.token())
                    .header("Idempotency-Key", "employee")
                    .contentType("application/json")
                    .content(json.writeValueAsString(Map.of("invoiceId", f.invoice()))))
            .andReturn()
            .getResponse();
    assertThat(denied.getStatus()).isEqualTo(403);
    create(f, "bad key", 400);
    var p = create(f, "scope", 200);
    send(GET, base(f.org()) + "/payments/" + p.get("paymentId").asText(), other.token(), null, 200);
    var otherOrg = organization(other);
    send(
        GET, base(otherOrg) + "/payments/" + p.get("paymentId").asText(), other.token(), null, 404);
    send(
        POST,
        base(f.org()) + "/payments/" + p.get("paymentId").asText() + "/cancel",
        other.token(),
        null,
        403);
  }

  @Test
  void concurrentCreatesReplayOneReservedIntent() throws Exception {
    var f = fixture();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<JsonNode> action =
          () -> {
            gate.await();
            return create(f, "concurrent", 200);
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      gate.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS).get("paymentId"))
          .isEqualTo(b.get(15, TimeUnit.SECONDS).get("paymentId"));
    }
    assertThat(intents).hasSize(1);
    create(f, "different", 409);
  }

  @Test
  void uncertainProviderFailureKeepsReservationForSameKeyRetry() throws Exception {
    var f = fixture();
    doThrow(
            new com.ledgerflow.shared.api.BusinessException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "PAYMENT_UNAVAILABLE",
                "Sandbox payments are unavailable."))
        .when(stripe)
        .create(any(), any(), any(), anyLong());
    create(f, "retry", 503);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.payments WHERE invoice_id=?",
                Integer.class,
                UUID.fromString(f.invoice())))
        .isEqualTo(1);
    reset(stripe);
    gateway();
    var p = create(f, "retry", 200);
    assertThat(p.has("providerId")).isTrue();
    create(f, "new-key", 409);
  }

  @Test
  void fullRefundReplaysRequestAndReopensReceivableThroughWebhook() throws Exception {
    var f = fixture();
    var p = create(f, "refund", 200);
    var pi = status(p, "succeeded");
    webhook("evt_" + UUID.randomUUID(), "payment_intent.succeeded", pi.id(), 200);
    var refund =
        new StripeGateway.Refund("re_" + UUID.randomUUID(), pi.id(), 10000, "usd", "succeeded");
    when(stripe.refund(eq(pi.id()), eq(10000L), any())).thenReturn(refund);
    when(stripe.retrieveRefund(refund.id())).thenReturn(refund);
    var path = base(f.org()) + "/payments/" + p.get("paymentId").asText() + "/refunds";
    for (int n = 0; n < 2; n++)
      assertThat(
              mvc.perform(
                      MockMvcRequestBuilders.post(path)
                          .header("Authorization", "Bearer " + f.owner().token())
                          .header("Idempotency-Key", "refund-key"))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .isEqualTo(202);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
    webhook("evt_" + UUID.randomUUID(), "refund.updated", refund.id(), 200);
    webhook("evt_" + UUID.randomUUID(), "refund.updated", refund.id(), 200);
    assertThat(postings(p)).isEqualTo(2);
    assertThat(invoiceStatus(f)).isEqualTo("ISSUED");
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(debit-credit) FROM ledgerflow.journal_entries WHERE organization_id=? AND account_code='STRIPE_CLEARING'",
                BigDecimal.class,
                UUID.fromString(f.org())))
        .isEqualByComparingTo("0");
  }

  @Test
  void paidPayoutAllocatesFeesAndMovesClearingIntoTransitOnce() throws Exception {
    var fixture = fixture();
    var secondFixture =
        new Fixture(
            fixture.owner(),
            fixture.org(),
            invoice(fixture.owner(), fixture.org(), "PAY-2", "50.00"));
    JsonNode payment = succeed(fixture, "payout-payment-one");
    JsonNode secondPayment = succeed(secondFixture, "payout-payment-two");
    String providerId = "po_single";
    when(stripe.retrievePayout(providerId))
        .thenReturn(
            new StripeGateway.Payout(
                providerId,
                14550,
                "usd",
                "paid",
                false,
                LocalDate.now().plusDays(2),
                List.of(
                    new StripeGateway.PayoutLine(
                        "txn_single",
                        "charge",
                        payment.get("providerId").asText(),
                        10000,
                        300,
                        9700,
                        "usd"),
                    new StripeGateway.PayoutLine(
                        "txn_second_same_org",
                        "charge",
                        secondPayment.get("providerId").asText(),
                        5000,
                        150,
                        4850,
                        "usd"))));
    JsonNode imported = importPayout(fixture, providerId, 200);
    JsonNode repeated = importPayout(fixture, providerId, 200);
    assertThat(repeated.get("id")).isEqualTo(imported.get("id"));
    assertThat(imported.get("gross").decimalValue()).isEqualByComparingTo("150.00");
    assertThat(imported.get("fee").decimalValue()).isEqualByComparingTo("4.50");
    assertThat(imported.get("amount").decimalValue()).isEqualByComparingTo("145.50");
    assertThat(imported.get("allocations")).hasSize(2);
    verify(stripe).retrievePayout(providerId);
    UUID organizationId = UUID.fromString(fixture.org());
    assertThat(accountBalance(organizationId, "STRIPE_CLEARING")).isEqualByComparingTo("0.00");
    assertThat(accountBalance(organizationId, "PAYOUTS_IN_TRANSIT")).isEqualByComparingTo("145.50");
    assertThat(accountBalance(organizationId, "PROCESSING_FEES")).isEqualByComparingTo("4.50");
    assertThat(accountBalance(organizationId, "CASH")).isEqualByComparingTo("0.00");
    UUID payoutId = UUID.fromString(imported.get("id").asText());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE payout_id=? AND operation='STRIPE_PAYOUT'",
                Integer.class,
                payoutId))
        .isEqualTo(2);
    assertThat(
            catchThrowable(
                () -> jdbc.update("DELETE FROM ledgerflow.stripe_payouts WHERE id=?", payoutId)))
        .isNotNull();
    when(stripe.retrievePayout("po_duplicatepayment"))
        .thenReturn(
            new StripeGateway.Payout(
                "po_duplicatepayment",
                9700,
                "usd",
                "paid",
                false,
                LocalDate.now().plusDays(3),
                List.of(
                    new StripeGateway.PayoutLine(
                        "txn_duplicate_payment",
                        "charge",
                        payment.get("providerId").asText(),
                        10000,
                        300,
                        9700,
                        "usd"))));
    importPayout(fixture, "po_duplicatepayment", 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.stripe_payouts WHERE organization_id=?",
                Integer.class,
                organizationId))
        .isOne();
  }

  @Test
  void mixedOrganizationPayoutIsRejectedWithoutPosting() throws Exception {
    var first = fixture();
    var second = fixture();
    JsonNode firstPayment = succeed(first, "payout-first");
    JsonNode secondPayment = succeed(second, "payout-second");
    String providerId = "po_mixed";
    when(stripe.retrievePayout(providerId))
        .thenReturn(
            new StripeGateway.Payout(
                providerId,
                19400,
                "usd",
                "paid",
                false,
                LocalDate.now().plusDays(2),
                List.of(
                    new StripeGateway.PayoutLine(
                        "txn_first",
                        "charge",
                        firstPayment.get("providerId").asText(),
                        10000,
                        300,
                        9700,
                        "usd"),
                    new StripeGateway.PayoutLine(
                        "txn_second",
                        "charge",
                        secondPayment.get("providerId").asText(),
                        10000,
                        300,
                        9700,
                        "usd"))));
    importPayout(first, providerId, 409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.stripe_payouts WHERE provider_id=?",
                Integer.class,
                providerId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=? AND operation='STRIPE_PAYOUT'",
                Integer.class,
                UUID.fromString(first.org())))
        .isZero();
    assertThat(accountBalance(UUID.fromString(first.org()), "STRIPE_CLEARING"))
        .isEqualByComparingTo("100.00");
  }

  @Test
  void employeesCanReadPayoutsButCannotImportThem() throws Exception {
    var fixture = fixture();
    var employee = register("payout-employee");
    addMember(fixture.org(), fixture.owner(), employee, "EMPLOYEE");
    send(GET, base(fixture.org()) + "/stripe-payouts", employee.token(), null, 200);
    send(
        POST,
        base(fixture.org()) + "/stripe-payouts/import",
        employee.token(),
        Map.of("providerPayoutId", "po_denied"),
        403);
    verify(stripe, never()).retrievePayout("po_denied");
  }

  @Test
  void refundBeforeSuccessNotificationStillPostsCorrectNetAmount() throws Exception {
    var f = fixture();
    var p = create(f, "early-refund", 200);
    var pi = status(p, "succeeded");
    var id = "re_" + UUID.randomUUID();
    when(stripe.retrieveRefund(id))
        .thenReturn(new StripeGateway.Refund(id, pi.id(), 2500, "usd", "pending"));
    webhook("evt_" + UUID.randomUUID(), "refund.created", id, 200);
    assertThat(postings(p)).isEqualTo(1);
    when(stripe.retrieveRefund(id))
        .thenReturn(new StripeGateway.Refund(id, pi.id(), 2500, "usd", "succeeded"));
    webhook("evt_" + UUID.randomUUID(), "refund.updated", id, 200);
    assertThat(postings(p)).isEqualTo(2);
    assertThat(invoiceStatus(f)).isEqualTo("ISSUED");
    webhook("evt_" + UUID.randomUUID(), "payment_intent.succeeded", pi.id(), 200);
    assertThat(invoiceStatus(f)).isEqualTo("ISSUED");
  }

  @Test
  void disputeReinstatementBeforeWithdrawalDoesNotDoublePost() throws Exception {
    var f = fixture();
    var p = create(f, "dispute-order", 200);
    var pi = status(p, "succeeded");
    var id = "dp_" + UUID.randomUUID();
    when(stripe.retrieveDispute(id))
        .thenReturn(new StripeGateway.Dispute(id, pi.id(), 10000, "usd", "won", false));
    webhook("evt_" + UUID.randomUUID(), "charge.dispute.funds_reinstated", id, 200);
    webhook("evt_" + UUID.randomUUID(), "charge.dispute.funds_withdrawn", id, 200);
    assertThat(postings(p)).isEqualTo(3);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
  }

  @Test
  void disputeWithdrawalReopensInvoiceUntilFundsReturn() throws Exception {
    var f = fixture();
    var p = create(f, "dispute", 200);
    var pi = status(p, "succeeded");
    var id = "dp_" + UUID.randomUUID();
    when(stripe.retrieveDispute(id))
        .thenReturn(new StripeGateway.Dispute(id, pi.id(), 10000, "usd", "needs_response", false));
    webhook("evt_" + UUID.randomUUID(), "charge.dispute.created", id, 200);
    assertThat(postings(p)).isEqualTo(1);
    webhook("evt_" + UUID.randomUUID(), "charge.dispute.funds_withdrawn", id, 200);
    assertThat(invoiceStatus(f)).isEqualTo("ISSUED");
    webhook("evt_" + UUID.randomUUID(), "charge.dispute.funds_reinstated", id, 200);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
  }

  @Test
  void activeIntentBlocksVoidUntilCanceled() throws Exception {
    var f = fixture();
    var p = create(f, "cancel", 200);
    var pi = status(p, "canceled");
    when(stripe.cancel(eq(pi.id()), anyString())).thenReturn(pi);
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/void",
        f.owner().token(),
        Map.of("version", 1),
        409);
    send(
        POST,
        base(f.org()) + "/payments/" + p.get("paymentId").asText() + "/cancel",
        f.owner().token(),
        null,
        200);
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/void",
        f.owner().token(),
        Map.of("version", 1),
        200);
  }

  @Test
  void invoiceSettlementFailureRollsBackPostingEventAndPaymentState() throws Exception {
    var f = fixture();
    var p = create(f, "rollback", 200);
    var pi = status(p, "succeeded");
    jdbc.update(
        "UPDATE ledgerflow.invoices SET status='VOID',voided_at=now() WHERE id=?",
        UUID.fromString(f.invoice()));
    var id = "evt_" + UUID.randomUUID();
    webhook(id, "payment_intent.succeeded", pi.id(), 409);
    assertThat(postings(p)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.stripe_events WHERE id=?", Integer.class, id))
        .isZero();
    assertThat(
            send(
                    GET,
                    base(f.org()) + "/payments/" + p.get("paymentId").asText(),
                    f.owner().token(),
                    null,
                    200)
                .get("state")
                .asText())
        .isEqualTo("PENDING");
  }

  @Test
  void webhookCanArriveBeforeIntentCreationResponseIsBound() throws Exception {
    var f = fixture();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              UUID id = call.getArgument(0),
                  org = call.getArgument(1),
                  invoice = call.getArgument(2);
              var intent =
                  new StripeGateway.Intent(
                      "pi_" + id,
                      call.getArgument(3),
                      "usd",
                      "succeeded",
                      false,
                      "synthetic_client_secret",
                      Map.of(
                          "ledgerflow_payment_id",
                          id.toString(),
                          "ledgerflow_organization_id",
                          org.toString(),
                          "ledgerflow_invoice_id",
                          invoice.toString()));
              intents.put(intent.id(), intent);
              webhook("evt_" + UUID.randomUUID(), "payment_intent.succeeded", intent.id(), 200);
              return intent;
            })
        .when(stripe)
        .create(any(), any(), any(), anyLong());
    var payment = create(f, "early-webhook", 200);
    assertThat(payment.get("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(postings(payment)).isEqualTo(1);
    assertThat(invoiceStatus(f)).isEqualTo("PAID");
  }

  @Test
  void unpayableInvoicesNeverReachStripeAndUpperBoundUsesExactCents() throws Exception {
    for (String price : List.of("0.00", "0.49", "1000000.00")) {
      create(fixture(price), "unpayable", 400);
    }
    verify(stripe, never()).create(any(), any(), any(), anyLong());
    var allowed = create(fixture("999999.99"), "upper-bound", 200);
    assertThat(intents.get(allowed.get("providerId").asText()).amount()).isEqualTo(99999999L);
  }

  BigDecimal accountBalance(UUID organizationId, String account) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(debit-credit),0) FROM ledgerflow.journal_entries WHERE organization_id=? AND account_code=?",
        BigDecimal.class,
        organizationId,
        account);
  }
}
