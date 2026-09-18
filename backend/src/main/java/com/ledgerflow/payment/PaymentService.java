package com.ledgerflow.payment;

import com.ledgerflow.invoicing.InvoicePaymentAccess;
import com.ledgerflow.ledger.PaymentLedger;
import com.ledgerflow.organization.*;
import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PaymentService {
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;
  private final InvoicePaymentAccess invoices;
  private final PaymentLedger ledger;
  private final StripeGateway stripe;
  private final StripeSettings settings;
  private final Clock clock;
  private final TransactionTemplate tx;

  PaymentService(
      JdbcTemplate jdbc,
      OrganizationAccess access,
      InvoicePaymentAccess invoices,
      PaymentLedger ledger,
      StripeGateway stripe,
      StripeSettings settings,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.invoices = invoices;
    this.ledger = ledger;
    this.stripe = stripe;
    this.settings = settings;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  record Payment(
      UUID id,
      UUID organizationId,
      UUID invoiceId,
      BigDecimal amount,
      String currency,
      String providerId,
      String state,
      Instant createdAt,
      String keyHash) {}

  record RefundRequest(UUID id, UUID paymentId, String providerId, Instant createdAt) {}

  record IntentResponse(UUID paymentId, String providerId, String clientSecret, String state) {}

  IntentResponse create(UUID org, UUID actor, UUID invoice, String key) {
    settings.requireEnabled();
    Payment payment =
        tx.execute(
            status -> {
              access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
              var snapshot = invoices.lock(org, invoice);
              var matches =
                  jdbc.query(
                      "SELECT * FROM ledgerflow.payments WHERE organization_id=? AND idempotency_hash=?",
                      this::payment,
                      org,
                      hash(key));
              if (!matches.isEmpty()) {
                var existing = matches.getFirst();
                if (!existing.invoiceId().equals(invoice))
                  throw BusinessException.conflict("Idempotency key belongs to another invoice.");
                return existing;
              }
              if (!snapshot.status().equals("ISSUED"))
                throw BusinessException.conflict("Only issued invoices can start payments.");
              long cents = cents(snapshot.total());
              if (cents < 50 || cents > 99999999)
                throw BusinessException.invalid(
                    "USD card payments must be between 0.50 and 999999.99.");
              if (jdbc.queryForObject(
                      "SELECT count(*) FROM ledgerflow.payments WHERE organization_id=? AND invoice_id=?",
                      Integer.class,
                      org,
                      invoice)
                  > 0)
                throw BusinessException.conflict(
                    "This invoice already has a payment attempt. Reuse its original idempotency key.");
              var id = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO ledgerflow.payments(id,organization_id,invoice_id,currency,amount,idempotency_hash,state,created_at) VALUES (?,?,?,'USD',?,?,'CREATING',?)",
                  id,
                  org,
                  invoice,
                  snapshot.total(),
                  hash(key),
                  Timestamp.from(clock.instant()));
              return require(org, id);
            });
    if (payment.providerId() == null) retryWindow(payment.createdAt());
    var intent =
        payment.providerId() == null
            ? stripe.create(payment.id(), org, invoice, cents(payment.amount()))
            : stripe.retrieve(payment.providerId());
    var current =
        tx.execute(
            status -> {
              access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
              invoices.lock(org, invoice);
              var locked = locked(org, payment.id());
              validate(locked, intent);
              bind(locked, intent.id());
              return require(org, payment.id());
            });
    return new IntentResponse(current.id(), intent.id(), intent.clientSecret(), current.state());
  }

  Payment get(UUID org, UUID actor, UUID id) {
    return tx.execute(
        status -> {
          access.require(org, actor);
          return require(org, id);
        });
  }

  Payment cancel(UUID org, UUID actor, UUID id) {
    var payment =
        tx.execute(
            status -> {
              access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
              return require(org, id);
            });
    if (payment.state().equals("SUCCEEDED"))
      throw BusinessException.conflict("A completed payment cannot be canceled.");
    if (payment.state().equals("CANCELED")) return payment;
    if (payment.providerId() == null)
      throw BusinessException.conflict("Finish intent creation before canceling.");
    var intent = stripe.cancel(payment.providerId(), "ledgerflow-cancel-" + id);
    return tx.execute(
        status -> {
          access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
          invoices.lock(org, payment.invoiceId());
          var current = locked(org, id);
          validate(current, intent);
          if (!intent.status().equals("canceled") || current.state().equals("SUCCEEDED"))
            throw BusinessException.conflict("A completed payment cannot be canceled.");
          jdbc.update("UPDATE ledgerflow.payments SET state='CANCELED' WHERE id=?", id);
          return require(org, id);
        });
  }

  RefundRequest refund(UUID org, UUID actor, UUID id, String key) {
    settings.requireEnabled();
    var request =
        tx.execute(
            status -> {
              access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
              var payment = require(org, id);
              invoices.lock(org, payment.invoiceId());
              payment = locked(org, id);
              var existing =
                  jdbc.query(
                      "SELECT * FROM ledgerflow.refund_requests WHERE organization_id=? AND idempotency_hash=?",
                      this::refundRequest,
                      org,
                      hash(key));
              if (!existing.isEmpty()) {
                if (!existing.getFirst().paymentId().equals(id))
                  throw BusinessException.conflict("Idempotency key belongs to another payment.");
                return existing.getFirst();
              }
              if (!payment.state().equals("SUCCEEDED"))
                throw BusinessException.conflict("Only successful payments can be refunded.");
              if (jdbc.queryForObject(
                          "SELECT count(*) FROM ledgerflow.refund_requests WHERE payment_id=?",
                          Integer.class,
                          id)
                      > 0
                  || ledger.hasAdjustments(id))
                throw BusinessException.conflict(
                    "This payment already has a refund or dispute adjustment.");
              var rid = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO ledgerflow.refund_requests(id,organization_id,payment_id,amount,currency,idempotency_hash,created_at) VALUES (?,?,?,?,'USD',?,?)",
                  rid,
                  org,
                  id,
                  payment.amount(),
                  hash(key),
                  Timestamp.from(clock.instant()));
              return new RefundRequest(rid, id, null, clock.instant());
            });
    if (request.providerId() == null) retryWindow(request.createdAt());
    var payment = get(org, actor, id);
    var refund =
        request.providerId() == null
            ? stripe.refund(payment.providerId(), cents(payment.amount()), request.id())
            : stripe.retrieveRefund(request.providerId());
    if (!Objects.equals(refund.intent(), payment.providerId())
        || !refund.currency().equals("usd")
        || refund.amount() != cents(payment.amount()))
      throw BusinessException.conflict("Provider refund does not match the payment.");
    return tx.execute(
        status -> {
          access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
          invoices.lock(org, payment.invoiceId());
          locked(org, id);
          jdbc.update(
              "UPDATE ledgerflow.refund_requests SET provider_id=? WHERE id=? AND (provider_id IS NULL OR provider_id=?)",
              refund.id(),
              request.id(),
              refund.id());
          return new RefundRequest(request.id(), id, refund.id(), request.createdAt());
        });
  }

  void event(
      String eventId,
      String type,
      StripeGateway.Intent intent,
      StripeGateway.Refund refund,
      StripeGateway.Dispute dispute) {
    UUID localId;
    try {
      localId = UUID.fromString(intent.metadata().get("ledgerflow_payment_id"));
    } catch (RuntimeException exception) {
      return;
    }
    var candidates =
        jdbc.query("SELECT * FROM ledgerflow.payments WHERE id=?", this::payment, localId);
    if (candidates.isEmpty()) return;
    var candidate = candidates.getFirst();
    tx.executeWithoutResult(
        status -> {
          invoices.lock(candidate.organizationId(), candidate.invoiceId());
          var payment = locked(candidate.organizationId(), candidate.id());
          validate(payment, intent);
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM ledgerflow.stripe_events WHERE id=?",
                  Integer.class,
                  eventId)
              > 0) return;
          bind(payment, intent.id());
          payment = require(payment.organizationId(), payment.id());
          if (intent.status().equals("succeeded")) {
            if (payment.state().equals("CANCELED"))
              throw BusinessException.conflict("Canceled payment cannot succeed.");
            ledger.post(
                payment.organizationId(),
                payment.invoiceId(),
                payment.id(),
                "PAYMENT",
                "payment:" + intent.id(),
                payment.amount(),
                clock.instant());
            jdbc.update(
                "UPDATE ledgerflow.payments SET state='SUCCEEDED' WHERE id=?", payment.id());
            if (refund != null && refund.status().equals("succeeded")) {
              adjustment(payment, refund.intent(), refund.currency(), refund.amount());
              ledger.post(
                  payment.organizationId(),
                  payment.invoiceId(),
                  payment.id(),
                  "REFUND",
                  "refund:" + refund.id(),
                  money(refund.amount()),
                  clock.instant());
            }
            if (dispute != null) {
              if (dispute.live()) throw BusinessException.invalid("Live events are not allowed.");
              adjustment(payment, dispute.intent(), dispute.currency(), dispute.amount());
              boolean restored = type.equals("charge.dispute.funds_reinstated");
              boolean removed = type.equals("charge.dispute.funds_withdrawn");
              if (restored || removed)
                ledger.post(
                    payment.organizationId(),
                    payment.invoiceId(),
                    payment.id(),
                    "DISPUTE_OUT",
                    "dispute-out:" + dispute.id(),
                    money(dispute.amount()),
                    clock.instant());
              if (restored)
                ledger.post(
                    payment.organizationId(),
                    payment.invoiceId(),
                    payment.id(),
                    "DISPUTE_IN",
                    "dispute-in:" + dispute.id(),
                    money(dispute.amount()),
                    clock.instant());
            }
            invoices.settle(
                payment.organizationId(), payment.invoiceId(), ledger.netPaid(payment.id()));
          } else if (!payment.state().equals("SUCCEEDED") && !payment.state().equals("CANCELED")) {
            String next =
                intent.status().equals("canceled")
                    ? "CANCELED"
                    : intent.status().equals("requires_payment_method") ? "FAILED" : "PENDING";
            jdbc.update("UPDATE ledgerflow.payments SET state=? WHERE id=?", next, payment.id());
          }
          jdbc.update(
              "INSERT INTO ledgerflow.stripe_events(id,type,payment_id,received_at) VALUES (?,?,?,?)",
              eventId,
              type,
              payment.id(),
              Timestamp.from(clock.instant()));
        });
  }

  boolean seen(String eventId) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.stripe_events WHERE id=?", Integer.class, eventId)
        > 0;
  }

  private void adjustment(Payment p, String intent, String currency, long amount) {
    if (!Objects.equals(intent, p.providerId())
        || !"usd".equals(currency)
        || amount <= 0
        || amount > cents(p.amount()))
      throw BusinessException.invalid("Payment adjustment does not match the invoice.");
  }

  private void bind(Payment payment, String id) {
    jdbc.update(
        "UPDATE ledgerflow.payments SET provider_id=?,state=CASE WHEN state='CREATING' THEN 'PENDING' ELSE state END WHERE id=?",
        id,
        payment.id());
  }

  private void validate(Payment p, StripeGateway.Intent i) {
    if (i.live()
        || !"usd".equals(i.currency())
        || i.amount() != cents(p.amount())
        || !p.id().toString().equals(i.metadata().get("ledgerflow_payment_id"))
        || !p.organizationId().toString().equals(i.metadata().get("ledgerflow_organization_id"))
        || !p.invoiceId().toString().equals(i.metadata().get("ledgerflow_invoice_id"))
        || (p.providerId() != null && !p.providerId().equals(i.id())))
      throw BusinessException.invalid("Provider payment does not match the invoice.");
  }

  private Payment require(UUID org, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.payments WHERE organization_id=? AND id=?",
            this::payment,
            org,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private Payment locked(UUID org, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.payments WHERE organization_id=? AND id=? FOR UPDATE",
            this::payment,
            org,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private Payment payment(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        rs.getObject("invoice_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("provider_id"),
        rs.getString("state"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("idempotency_hash"));
  }

  private RefundRequest refundRequest(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new RefundRequest(
        rs.getObject("id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getString("provider_id"),
        rs.getTimestamp("created_at").toInstant());
  }

  private void retryWindow(Instant created) {
    if (created.isBefore(clock.instant().minus(Duration.ofHours(23))))
      throw BusinessException.conflict(
          "Unresolved provider request needs recovery before retrying.");
  }

  private static long cents(BigDecimal amount) {
    return amount.movePointRight(2).longValueExact();
  }

  private static BigDecimal money(long cents) {
    return BigDecimal.valueOf(cents, 2);
  }

  private static String hash(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
