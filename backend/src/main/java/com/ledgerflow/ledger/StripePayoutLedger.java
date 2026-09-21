package com.ledgerflow.ledger;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StripePayoutLedger {
  private final JdbcTemplate jdbc;

  public StripePayoutLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void post(
      UUID organizationId,
      UUID invoiceId,
      UUID paymentId,
      UUID payoutId,
      String providerBalanceTransactionId,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal net,
      Instant at) {
    if (gross.signum() <= 0
        || fee.signum() < 0
        || net.signum() <= 0
        || gross.subtract(fee).compareTo(net) != 0)
      throw new IllegalArgumentException("Invalid payout allocation");
    String source = "stripe-balance:" + providerBalanceTransactionId;
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=? AND source_key=?",
            Integer.class,
            organizationId,
            source)
        > 0) return;
    UUID journal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,payment_id,payout_id,operation,source_key,currency,posted_at) VALUES (?,?,?,?,?,'STRIPE_PAYOUT',?,'USD',?)",
        journal,
        organizationId,
        invoiceId,
        paymentId,
        payoutId,
        source,
        Timestamp.from(at));
    entry(organizationId, journal, "PAYOUTS_IN_TRANSIT", net, BigDecimal.ZERO);
    if (fee.signum() > 0) entry(organizationId, journal, "PROCESSING_FEES", fee, BigDecimal.ZERO);
    entry(organizationId, journal, "STRIPE_CLEARING", BigDecimal.ZERO, gross);
  }

  private void entry(
      UUID organizationId, UUID journalId, String account, BigDecimal debit, BigDecimal credit) {
    jdbc.update(
        "INSERT INTO ledgerflow.journal_entries(id,organization_id,journal_id,account_code,currency,debit,credit) VALUES (?,?,?,?,'USD',?,?)",
        UUID.randomUUID(),
        organizationId,
        journalId,
        account,
        debit,
        credit);
  }
}
