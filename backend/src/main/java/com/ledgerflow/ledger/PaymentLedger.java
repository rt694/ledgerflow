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
public class PaymentLedger {
  private final JdbcTemplate jdbc;

  public PaymentLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void post(
      UUID org,
      UUID invoice,
      UUID payment,
      String operation,
      String source,
      BigDecimal amount,
      Instant at) {
    if (!java.util.Set.of("PAYMENT", "REFUND", "DISPUTE_OUT", "DISPUTE_IN").contains(operation)
        || amount.signum() <= 0) throw new IllegalArgumentException("Invalid payment posting");
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=? AND source_key=?",
            Integer.class,
            org,
            source)
        > 0) return;
    var id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,payment_id,operation,source_key,currency,posted_at) VALUES (?,?,?,?,?,?,'USD',?)",
        id,
        org,
        invoice,
        payment,
        operation,
        source,
        Timestamp.from(at));
    boolean incoming = operation.equals("PAYMENT") || operation.equals("DISPUTE_IN");
    entry(org, id, incoming ? "STRIPE_CLEARING" : "RECEIVABLES", amount, BigDecimal.ZERO);
    entry(org, id, incoming ? "RECEIVABLES" : "STRIPE_CLEARING", BigDecimal.ZERO, amount);
  }

  public BigDecimal netPaid(UUID payment) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(e.credit-e.debit),0) FROM ledgerflow.journal_entries e JOIN ledgerflow.journal_transactions j ON j.id=e.journal_id WHERE j.payment_id=? AND e.account_code='RECEIVABLES'",
        BigDecimal.class,
        payment);
  }

  public boolean hasAdjustments(UUID payment) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.journal_transactions WHERE payment_id=? AND operation<>'PAYMENT'",
            Integer.class,
            payment)
        > 0;
  }

  private void entry(UUID org, UUID journal, String account, BigDecimal debit, BigDecimal credit) {
    jdbc.update(
        "INSERT INTO ledgerflow.journal_entries(id,organization_id,journal_id,account_code,currency,debit,credit) VALUES (?,?,?,?,'USD',?,?)",
        UUID.randomUUID(),
        org,
        journal,
        account,
        debit,
        credit);
  }
}
