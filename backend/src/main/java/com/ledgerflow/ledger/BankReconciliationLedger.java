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
public class BankReconciliationLedger {
  private final JdbcTemplate jdbc;

  public BankReconciliationLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void postPayment(
      UUID organizationId,
      UUID invoiceId,
      UUID bankTransactionId,
      String providerTransactionId,
      BigDecimal amount,
      Instant at) {
    if (amount.signum() <= 0) throw new IllegalArgumentException("Invalid bank payment amount");
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=? AND bank_transaction_id=?",
            Integer.class,
            organizationId,
            bankTransactionId)
        > 0) return;
    UUID journal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,bank_transaction_id,operation,source_key,currency,posted_at) VALUES (?,?,?,?,'BANK_PAYMENT',?,'USD',?)",
        journal,
        organizationId,
        invoiceId,
        bankTransactionId,
        "bank:" + providerTransactionId,
        Timestamp.from(at));
    entry(organizationId, journal, "CASH", amount, BigDecimal.ZERO);
    entry(organizationId, journal, "RECEIVABLES", BigDecimal.ZERO, amount);
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
