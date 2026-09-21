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
public class PayoutDepositLedger {
  private final JdbcTemplate jdbc;

  public PayoutDepositLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void post(
      UUID organizationId,
      UUID payoutId,
      UUID bankTransactionId,
      String providerTransactionId,
      BigDecimal amount,
      Instant at) {
    if (amount.signum() <= 0) throw new IllegalArgumentException("Invalid payout deposit amount");
    UUID journal = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO ledgerflow.journal_transactions(id,organization_id,payout_id,bank_transaction_id,operation,source_key,currency,posted_at) VALUES (?,?,?,?,'PAYOUT_DEPOSIT',?,'USD',?)",
        journal,
        organizationId,
        payoutId,
        bankTransactionId,
        "bank:" + providerTransactionId,
        Timestamp.from(at));
    entry(organizationId, journal, "CASH", amount, BigDecimal.ZERO);
    entry(organizationId, journal, "PAYOUTS_IN_TRANSIT", BigDecimal.ZERO, amount);
  }

  public BigDecimal transitBalance(UUID organizationId, UUID payoutId) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(e.debit-e.credit),0) FROM ledgerflow.journal_entries e JOIN ledgerflow.journal_transactions j ON j.organization_id=e.organization_id AND j.id=e.journal_id WHERE j.organization_id=? AND j.payout_id=? AND e.account_code='PAYOUTS_IN_TRANSIT'",
        BigDecimal.class,
        organizationId,
        payoutId);
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
