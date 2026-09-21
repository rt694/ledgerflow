package com.ledgerflow.payment;

import com.ledgerflow.ledger.StripePayoutLedger;
import com.ledgerflow.organization.*;
import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class StripePayoutService {
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;
  private final StripeGateway stripe;
  private final StripeSettings settings;
  private final StripePayoutLedger ledger;
  private final Clock clock;
  private final TransactionTemplate tx;

  StripePayoutService(
      JdbcTemplate jdbc,
      OrganizationAccess access,
      StripeGateway stripe,
      StripeSettings settings,
      StripePayoutLedger ledger,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.stripe = stripe;
    this.settings = settings;
    this.ledger = ledger;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
  }

  record Payout(
      UUID id,
      String providerId,
      BigDecimal amount,
      BigDecimal gross,
      BigDecimal fee,
      String currency,
      LocalDate arrivalDate,
      Instant importedAt,
      UUID bankTransactionId,
      Instant depositedAt,
      List<Allocation> allocations) {}

  record PayoutSummary(
      UUID id,
      String providerId,
      BigDecimal amount,
      BigDecimal gross,
      BigDecimal fee,
      String currency,
      LocalDate arrivalDate,
      Instant importedAt,
      UUID bankTransactionId,
      Instant depositedAt,
      int allocationCount) {}

  record Allocation(
      UUID paymentId,
      UUID invoiceId,
      String providerBalanceTransactionId,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net) {}

  Payout importPayout(UUID organizationId, UUID actor, String providerId) {
    settings.requireEnabled();
    Payout existing =
        tx.execute(
            status -> {
              access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
              return findByProvider(organizationId, providerId).orElse(null);
            });
    if (existing != null) return existing;
    StripeGateway.Payout provider = stripe.retrievePayout(providerId);
    validateProvider(providerId, provider);
    return tx.execute(
        status -> {
          access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
          jdbc.queryForObject(
              "SELECT id FROM ledgerflow.organizations WHERE id=? FOR UPDATE",
              UUID.class,
              organizationId);
          var repeated = findByProvider(organizationId, providerId);
          if (repeated.isPresent()) return repeated.get();
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM ledgerflow.stripe_payouts WHERE provider_id=?",
                  Integer.class,
                  providerId)
              > 0)
            throw BusinessException.conflict(
                "This payout cannot be assigned to this organization.");
          List<ResolvedLine> lines = resolve(organizationId, provider);
          BigDecimal gross = lines.stream().map(ResolvedLine::gross).reduce(ZERO, BigDecimal::add);
          BigDecimal fee = lines.stream().map(ResolvedLine::fee).reduce(ZERO, BigDecimal::add);
          BigDecimal amount = lines.stream().map(ResolvedLine::net).reduce(ZERO, BigDecimal::add);
          if (amount.compareTo(money(provider.amount())) != 0)
            throw BusinessException.conflict("Stripe payout totals do not balance.");
          UUID payoutId = UUID.randomUUID();
          Instant now = clock.instant();
          jdbc.update(
              "INSERT INTO ledgerflow.stripe_payouts(id,organization_id,provider_id,currency,gross,fee,amount,arrival_date,imported_at) VALUES (?,?,?,'USD',?,?,?,?,?)",
              payoutId,
              organizationId,
              providerId,
              gross,
              fee,
              amount,
              provider.arrivalDate(),
              Timestamp.from(now));
          Instant postedAt = provider.arrivalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
          for (ResolvedLine line : lines) {
            jdbc.update(
                "INSERT INTO ledgerflow.stripe_payout_allocations(id,organization_id,payout_id,payment_id,provider_balance_transaction_id,gross,fee,net) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                organizationId,
                payoutId,
                line.paymentId(),
                line.balanceTransactionId(),
                line.gross(),
                line.fee(),
                line.net());
            ledger.post(
                organizationId,
                line.invoiceId(),
                line.paymentId(),
                payoutId,
                line.balanceTransactionId(),
                line.gross(),
                line.fee(),
                line.net(),
                postedAt);
          }
          return require(organizationId, payoutId);
        });
  }

  List<PayoutSummary> list(UUID organizationId, UUID actor) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          return jdbc.query(
              "SELECT p.*,(SELECT count(*) FROM ledgerflow.stripe_payout_allocations a WHERE a.payout_id=p.id) allocation_count,(SELECT j.bank_transaction_id FROM ledgerflow.journal_transactions j WHERE j.payout_id=p.id AND j.operation='PAYOUT_DEPOSIT') bank_transaction_id,(SELECT j.posted_at FROM ledgerflow.journal_transactions j WHERE j.payout_id=p.id AND j.operation='PAYOUT_DEPOSIT') deposited_at FROM ledgerflow.stripe_payouts p WHERE p.organization_id=? ORDER BY p.arrival_date DESC,p.id LIMIT 100",
              (rs, row) ->
                  new PayoutSummary(
                      rs.getObject("id", UUID.class),
                      rs.getString("provider_id"),
                      rs.getBigDecimal("amount"),
                      rs.getBigDecimal("gross"),
                      rs.getBigDecimal("fee"),
                      rs.getString("currency"),
                      rs.getObject("arrival_date", LocalDate.class),
                      rs.getTimestamp("imported_at").toInstant(),
                      rs.getObject("bank_transaction_id", UUID.class),
                      instant(rs, "deposited_at"),
                      rs.getInt("allocation_count")),
              organizationId);
        });
  }

  Payout get(UUID organizationId, UUID actor, UUID id) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          return require(organizationId, id);
        });
  }

  private List<ResolvedLine> resolve(UUID organizationId, StripeGateway.Payout provider) {
    var seenTransactions = new HashSet<String>();
    var seenPayments = new HashSet<UUID>();
    var result = new ArrayList<ResolvedLine>();
    for (StripeGateway.PayoutLine line : provider.lines()) {
      if (!"charge".equals(line.type())
          || line.intent() == null
          || !"usd".equals(line.currency())
          || line.gross() <= 0
          || line.fee() < 0
          || line.net() <= 0
          || line.gross() - line.fee() != line.net()
          || !seenTransactions.add(line.balanceTransactionId()))
        throw BusinessException.conflict(
            "The payout contains unsupported or duplicate Stripe activity.");
      var payment =
          jdbc
              .query(
                  "SELECT id,invoice_id,amount,state FROM ledgerflow.payments WHERE organization_id=? AND provider_id=?",
                  (rs, row) ->
                      new PaymentSnapshot(
                          rs.getObject("id", UUID.class),
                          rs.getObject("invoice_id", UUID.class),
                          rs.getBigDecimal("amount"),
                          rs.getString("state")),
                  organizationId,
                  line.intent())
              .stream()
              .findFirst()
              .orElseThrow(
                  () ->
                      BusinessException.conflict(
                          "The payout contains activity outside this organization."));
      if (!"SUCCEEDED".equals(payment.state())
          || payment.amount().movePointRight(2).longValueExact() != line.gross()
          || !seenPayments.add(payment.id()))
        throw BusinessException.conflict("The payout does not match completed local payments.");
      if (jdbc.queryForObject(
              "SELECT count(*) FROM ledgerflow.stripe_payout_allocations WHERE payment_id=?",
              Integer.class,
              payment.id())
          > 0) throw BusinessException.conflict("A payment cannot be allocated to two payouts.");
      result.add(
          new ResolvedLine(
              payment.id(),
              payment.invoiceId(),
              line.balanceTransactionId(),
              money(line.gross()),
              money(line.fee()),
              money(line.net())));
    }
    if (result.isEmpty()) throw BusinessException.conflict("The payout has no supported charges.");
    return List.copyOf(result);
  }

  private void validateProvider(String requestedId, StripeGateway.Payout value) {
    if (!requestedId.equals(value.id())
        || value.live()
        || !"usd".equals(value.currency())
        || !"paid".equals(value.status())
        || value.amount() <= 0
        || value.arrivalDate() == null
        || value.lines() == null
        || value.lines().size() > 1000)
      throw BusinessException.conflict("Stripe payout is not ready for allocation.");
  }

  private Optional<Payout> findByProvider(UUID organizationId, String providerId) {
    var ids =
        jdbc.query(
            "SELECT id FROM ledgerflow.stripe_payouts WHERE organization_id=? AND provider_id=?",
            (rs, row) -> rs.getObject("id", UUID.class),
            organizationId,
            providerId);
    return ids.isEmpty() ? Optional.empty() : Optional.of(require(organizationId, ids.getFirst()));
  }

  private Payout require(UUID organizationId, UUID id) {
    var header =
        jdbc
            .query(
                "SELECT p.*,(SELECT j.bank_transaction_id FROM ledgerflow.journal_transactions j WHERE j.payout_id=p.id AND j.operation='PAYOUT_DEPOSIT') bank_transaction_id,(SELECT j.posted_at FROM ledgerflow.journal_transactions j WHERE j.payout_id=p.id AND j.operation='PAYOUT_DEPOSIT') deposited_at FROM ledgerflow.stripe_payouts p WHERE p.organization_id=? AND p.id=?",
                (rs, row) ->
                    new PayoutHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider_id"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("gross"),
                        rs.getBigDecimal("fee"),
                        rs.getString("currency"),
                        rs.getObject("arrival_date", LocalDate.class),
                        rs.getTimestamp("imported_at").toInstant(),
                        rs.getObject("bank_transaction_id", UUID.class),
                        instant(rs, "deposited_at")),
                organizationId,
                id)
            .stream()
            .findFirst()
            .orElseThrow(BusinessException::notFound);
    var allocations =
        jdbc.query(
            "SELECT a.*,p.invoice_id FROM ledgerflow.stripe_payout_allocations a JOIN ledgerflow.payments p ON p.organization_id=a.organization_id AND p.id=a.payment_id WHERE a.organization_id=? AND a.payout_id=? ORDER BY a.provider_balance_transaction_id",
            (rs, row) ->
                new Allocation(
                    rs.getObject("payment_id", UUID.class),
                    rs.getObject("invoice_id", UUID.class),
                    rs.getString("provider_balance_transaction_id"),
                    rs.getBigDecimal("gross"),
                    rs.getBigDecimal("fee"),
                    rs.getBigDecimal("net")),
            organizationId,
            id);
    return new Payout(
        header.id(),
        header.providerId(),
        header.amount(),
        header.gross(),
        header.fee(),
        header.currency(),
        header.arrivalDate(),
        header.importedAt(),
        header.bankTransactionId(),
        header.depositedAt(),
        allocations);
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private BigDecimal money(long cents) {
    return BigDecimal.valueOf(cents, 2);
  }

  private static final BigDecimal ZERO = new BigDecimal("0.00");

  private record PaymentSnapshot(UUID id, UUID invoiceId, BigDecimal amount, String state) {}

  private record ResolvedLine(
      UUID paymentId,
      UUID invoiceId,
      String balanceTransactionId,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net) {}

  private record PayoutHeader(
      UUID id,
      String providerId,
      BigDecimal amount,
      BigDecimal gross,
      BigDecimal fee,
      String currency,
      LocalDate arrivalDate,
      Instant importedAt,
      UUID bankTransactionId,
      Instant depositedAt) {}
}
