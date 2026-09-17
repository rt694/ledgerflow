package com.ledgerflow.ledger;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Invoice state and journal entries must share the caller's transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InvoiceLedger {
  private final JdbcTemplate jdbc;

  public InvoiceLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void issue(UUID org, UUID invoice, BigDecimal subtotal, BigDecimal tax, Instant at) {
    BigDecimal total = subtotal.add(tax);
    if (total.signum() == 0) return;
    UUID journal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at) VALUES (?,?,?,'ISSUE','USD',?)",
        journal,
        org,
        invoice,
        Timestamp.from(at));
    entry(org, journal, "RECEIVABLES", total, BigDecimal.ZERO);
    if (subtotal.signum() > 0) entry(org, journal, "REVENUE", BigDecimal.ZERO, subtotal);
    if (tax.signum() > 0) entry(org, journal, "TAX_PAYABLE", BigDecimal.ZERO, tax);
  }

  public void reverse(UUID org, UUID invoice, BigDecimal total, Instant at) {
    if (total.signum() == 0) return;
    UUID journal = UUID.randomUUID();
    int inserted =
        jdbc.update(
            "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at,reverses_id) SELECT ?,organization_id,invoice_id,'VOID',currency,?,id FROM ledgerflow.journal_transactions WHERE organization_id = ? AND invoice_id = ? AND operation = 'ISSUE'",
            journal,
            Timestamp.from(at),
            org,
            invoice);
    if (inserted != 1) throw new IllegalStateException("Missing invoice posting");
    jdbc.update(
        "INSERT INTO ledgerflow.journal_entries(id,organization_id,journal_id,account_code,currency,debit,credit) SELECT gen_random_uuid(),organization_id,?,account_code,currency,credit,debit FROM ledgerflow.journal_entries WHERE journal_id = (SELECT reverses_id FROM ledgerflow.journal_transactions WHERE id = ?)",
        journal,
        journal);
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
