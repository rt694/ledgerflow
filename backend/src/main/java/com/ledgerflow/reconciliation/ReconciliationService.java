package com.ledgerflow.reconciliation;

import com.ledgerflow.invoicing.InvoicePaymentAccess;
import com.ledgerflow.ledger.BankReconciliationLedger;
import com.ledgerflow.ledger.PayoutDepositLedger;
import com.ledgerflow.organization.*;
import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReconciliationService {
  private static final String CASE_SELECT =
      "SELECT c.*,bt.name bank_description,bt.amount bank_amount,bt.currency,bt.transaction_date,"
          + "(SELECT count(*) FROM ledgerflow.reconciliation_candidates x WHERE x.case_id=c.id) invoice_candidate_count,"
          + "(SELECT count(*) FROM ledgerflow.payout_reconciliation_candidates x WHERE x.case_id=c.id) payout_candidate_count "
          + "FROM ledgerflow.reconciliation_cases c JOIN ledgerflow.bank_transactions bt ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id";
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;
  private final InvoicePaymentAccess invoices;
  private final BankReconciliationLedger ledger;
  private final PayoutDepositLedger payoutLedger;
  private final Clock clock;

  ReconciliationService(
      JdbcTemplate jdbc,
      OrganizationAccess access,
      InvoicePaymentAccess invoices,
      BankReconciliationLedger ledger,
      PayoutDepositLedger payoutLedger,
      Clock clock) {
    this.jdbc = jdbc;
    this.access = access;
    this.invoices = invoices;
    this.ledger = ledger;
    this.payoutLedger = payoutLedger;
    this.clock = clock;
  }

  record RefreshResult(int newCases, int candidates, int invoiceCandidates, int payoutCandidates) {}

  record ReconciliationCase(
      UUID id,
      UUID bankTransactionId,
      String bankDescription,
      BigDecimal bankAmount,
      String currency,
      LocalDate transactionDate,
      String status,
      UUID matchedInvoiceId,
      UUID matchedPayoutId,
      BigDecimal matchedAmount,
      long version,
      int candidateCount,
      int invoiceCandidateCount,
      int payoutCandidateCount,
      String reviewState,
      Instant createdAt,
      Instant updatedAt) {}

  record Candidate(
      UUID invoiceId,
      String invoiceNumber,
      BigDecimal invoiceTotal,
      BigDecimal outstandingBalance,
      LocalDate dueDate,
      BigDecimal score,
      String rule) {}

  record PayoutCandidate(
      UUID payoutId,
      String providerId,
      BigDecimal amount,
      LocalDate arrivalDate,
      BigDecimal score,
      String rule) {}

  record Decision(
      String decision,
      UUID invoiceId,
      UUID payoutId,
      BigDecimal amount,
      UUID decidedBy,
      Instant decidedAt) {}

  record Detail(
      ReconciliationCase reconciliationCase,
      List<Candidate> candidates,
      List<PayoutCandidate> payoutCandidates,
      List<Decision> decisions) {}

  @Transactional
  RefreshResult refresh(UUID organizationId, UUID actor) {
    access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
    Instant now = clock.instant();
    int cases =
        jdbc.update(
            "INSERT INTO ledgerflow.reconciliation_cases(id,organization_id,bank_transaction_id,status,created_at,updated_at) SELECT gen_random_uuid(),bt.organization_id,bt.id,'OPEN',?,? FROM ledgerflow.bank_transactions bt WHERE bt.organization_id=? AND bt.amount<0 AND bt.currency='USD' AND bt.pending=false AND bt.removed_at IS NULL ON CONFLICT (organization_id,bank_transaction_id) DO NOTHING",
            Timestamp.from(now),
            Timestamp.from(now),
            organizationId);
    jdbc.update(
        "DELETE FROM ledgerflow.reconciliation_candidates rc USING ledgerflow.reconciliation_cases c WHERE rc.case_id=c.id AND c.organization_id=? AND c.status='OPEN'",
        organizationId);
    jdbc.update(
        "DELETE FROM ledgerflow.payout_reconciliation_candidates pc USING ledgerflow.reconciliation_cases c WHERE pc.case_id=c.id AND c.organization_id=? AND c.status='OPEN'",
        organizationId);
    int invoiceCandidates =
        jdbc.update(
            """
            INSERT INTO ledgerflow.reconciliation_candidates
              (id,organization_id,case_id,invoice_id,score,rule,created_at)
            SELECT gen_random_uuid(),c.organization_id,c.id,i.id,
              CASE
                WHEN position(lower(i.invoice_number) in lower(bt.name))>0
                     AND abs(bt.amount)=balance.outstanding THEN 1.0000
                WHEN position(lower(i.invoice_number) in lower(bt.name))>0 THEN 0.9000
                ELSE 0.8500
              END,
              CASE
                WHEN position(lower(i.invoice_number) in lower(bt.name))>0
                     AND abs(bt.amount)=balance.outstanding THEN 'REFERENCE_AND_EXACT_AMOUNT'
                WHEN position(lower(i.invoice_number) in lower(bt.name))>0 THEN 'REFERENCE_PARTIAL_AMOUNT'
                ELSE 'EXACT_AMOUNT_DATE_WINDOW'
              END,?
            FROM ledgerflow.reconciliation_cases c
            JOIN ledgerflow.bank_transactions bt
              ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id
            JOIN ledgerflow.invoices i
              ON i.organization_id=c.organization_id
             AND i.status='ISSUED'
             AND i.currency=bt.currency
             AND bt.transaction_date>=timezone('UTC',i.issued_at)::date
             AND bt.transaction_date<=i.due_date+30
            CROSS JOIN LATERAL (
              SELECT coalesce(sum(e.debit-e.credit),0) outstanding
              FROM ledgerflow.journal_transactions j
              JOIN ledgerflow.journal_entries e
                ON e.organization_id=j.organization_id AND e.journal_id=j.id
              WHERE j.organization_id=i.organization_id
                AND j.invoice_id=i.id
                AND e.account_code='RECEIVABLES'
            ) balance
            WHERE c.organization_id=?
              AND c.status='OPEN'
              AND bt.amount<0
              AND bt.currency='USD'
              AND bt.pending=false
              AND bt.removed_at IS NULL
              AND bt.amount=round(bt.amount,2)
              AND NOT EXISTS (
                SELECT 1 FROM ledgerflow.payments p
                WHERE p.organization_id=i.organization_id
                  AND p.invoice_id=i.id
                  AND p.state<>'CANCELED'
              )
              AND abs(bt.amount)<=balance.outstanding
              AND (abs(bt.amount)=balance.outstanding
                   OR position(lower(i.invoice_number) in lower(bt.name))>0)
            """,
            Timestamp.from(now),
            organizationId);
    int payoutCandidates =
        jdbc.update(
            """
            INSERT INTO ledgerflow.payout_reconciliation_candidates
              (id,organization_id,case_id,payout_id,score,rule,created_at)
            SELECT gen_random_uuid(),c.organization_id,c.id,p.id,
              CASE WHEN position(lower(p.provider_id) in lower(bt.name))>0
                   THEN 1.0000 ELSE 0.9500 END,
              CASE WHEN position(lower(p.provider_id) in lower(bt.name))>0
                   THEN 'PAYOUT_REFERENCE_AND_AMOUNT'
                   ELSE 'PAYOUT_AMOUNT_DATE_WINDOW' END,?
            FROM ledgerflow.reconciliation_cases c
            JOIN ledgerflow.bank_transactions bt
              ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id
            JOIN ledgerflow.stripe_payouts p
              ON p.organization_id=c.organization_id
             AND p.currency=bt.currency
             AND p.amount=abs(bt.amount)
             AND bt.transaction_date BETWEEN p.arrival_date-3 AND p.arrival_date+7
            WHERE c.organization_id=?
              AND c.status='OPEN'
              AND bt.amount<0
              AND bt.currency='USD'
              AND bt.pending=false
              AND bt.removed_at IS NULL
              AND bt.amount=round(bt.amount,2)
              AND NOT EXISTS (
                SELECT 1 FROM ledgerflow.journal_transactions j
                WHERE j.organization_id=p.organization_id
                  AND j.payout_id=p.id
                  AND j.operation='PAYOUT_DEPOSIT'
              )
            """,
            Timestamp.from(now),
            organizationId);
    return new RefreshResult(
        cases, invoiceCandidates + payoutCandidates, invoiceCandidates, payoutCandidates);
  }

  @Transactional(readOnly = true)
  List<ReconciliationCase> list(UUID organizationId, UUID actor, String status) {
    access.require(organizationId, actor);
    String normalized = status == null ? null : status.toUpperCase(Locale.ROOT);
    if (normalized != null && !Set.of("OPEN", "MATCHED", "IGNORED").contains(normalized))
      throw BusinessException.invalid("Unknown reconciliation status.");
    return jdbc.query(
        CASE_SELECT
            + " WHERE c.organization_id=? AND (?::varchar IS NULL OR c.status=?::varchar) ORDER BY c.created_at,c.id LIMIT 100",
        this::reconciliationCase,
        organizationId,
        normalized,
        normalized);
  }

  @Transactional(readOnly = true)
  Detail get(UUID organizationId, UUID actor, UUID id) {
    access.require(organizationId, actor);
    ReconciliationCase value = requireCase(organizationId, id, false);
    var candidates =
        jdbc.query(
            "SELECT rc.invoice_id,i.invoice_number,i.total,i.due_date,rc.score,rc.rule,coalesce(sum(e.debit-e.credit),0) outstanding_balance FROM ledgerflow.reconciliation_candidates rc JOIN ledgerflow.invoices i ON i.organization_id=rc.organization_id AND i.id=rc.invoice_id JOIN ledgerflow.journal_transactions j ON j.organization_id=i.organization_id AND j.invoice_id=i.id JOIN ledgerflow.journal_entries e ON e.organization_id=j.organization_id AND e.journal_id=j.id AND e.account_code='RECEIVABLES' WHERE rc.organization_id=? AND rc.case_id=? GROUP BY rc.invoice_id,i.invoice_number,i.total,i.due_date,rc.score,rc.rule,i.id ORDER BY rc.score DESC,i.due_date,i.id",
            (rs, row) ->
                new Candidate(
                    rs.getObject("invoice_id", UUID.class),
                    rs.getString("invoice_number"),
                    rs.getBigDecimal("total"),
                    rs.getBigDecimal("outstanding_balance"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getBigDecimal("score"),
                    rs.getString("rule")),
            organizationId,
            id);
    var payoutCandidates =
        jdbc.query(
            "SELECT pc.payout_id,p.provider_id,p.amount,p.arrival_date,pc.score,pc.rule FROM ledgerflow.payout_reconciliation_candidates pc JOIN ledgerflow.stripe_payouts p ON p.organization_id=pc.organization_id AND p.id=pc.payout_id WHERE pc.organization_id=? AND pc.case_id=? ORDER BY pc.score DESC,p.arrival_date,p.id",
            (rs, row) ->
                new PayoutCandidate(
                    rs.getObject("payout_id", UUID.class),
                    rs.getString("provider_id"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("arrival_date", LocalDate.class),
                    rs.getBigDecimal("score"),
                    rs.getString("rule")),
            organizationId,
            id);
    var decisions =
        jdbc.query(
            "SELECT decision,invoice_id,payout_id,amount,decided_by,decided_at FROM ledgerflow.reconciliation_decisions WHERE organization_id=? AND case_id=? ORDER BY decided_at,id",
            (rs, row) ->
                new Decision(
                    rs.getString("decision"),
                    rs.getObject("invoice_id", UUID.class),
                    rs.getObject("payout_id", UUID.class),
                    rs.getBigDecimal("amount"),
                    rs.getObject("decided_by", UUID.class),
                    rs.getTimestamp("decided_at").toInstant()),
            organizationId,
            id);
    return new Detail(value, candidates, payoutCandidates, decisions);
  }

  @Transactional
  Detail match(UUID organizationId, UUID actor, UUID id, UUID invoiceId, long version) {
    access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
    ReconciliationCase current = requireCase(organizationId, id, true);
    requireOpenVersion(current, version);
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.reconciliation_candidates WHERE organization_id=? AND case_id=? AND invoice_id=?",
            Integer.class,
            organizationId,
            id,
            invoiceId)
        != 1) throw BusinessException.invalid("Choose one of the current invoice candidates.");
    var bank =
        jdbc
            .query(
                "SELECT provider_transaction_id,amount,currency,transaction_date,pending,removed_at FROM ledgerflow.bank_transactions WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, row) ->
                    new BankSnapshot(
                        rs.getString("provider_transaction_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("transaction_date", LocalDate.class),
                        rs.getBoolean("pending"),
                        rs.getTimestamp("removed_at") != null),
                organizationId,
                current.bankTransactionId())
            .stream()
            .findFirst()
            .orElseThrow(BusinessException::notFound);
    if (bank.pending()
        || bank.removed()
        || bank.amount().signum() >= 0
        || !"USD".equals(bank.currency()))
      throw BusinessException.conflict("This bank transaction can no longer settle an invoice.");
    var invoice = invoices.lock(organizationId, invoiceId);
    BigDecimal received = bank.amount().abs();
    BigDecimal outstanding = ledger.receivableBalance(organizationId, invoiceId);
    boolean activeCardPayment =
        jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.payments WHERE organization_id=? AND invoice_id=? AND state<>'CANCELED'",
                Integer.class,
                organizationId,
                invoiceId)
            > 0;
    if (activeCardPayment
        || received.remainder(new BigDecimal("0.01")).signum() != 0
        || !"ISSUED".equals(invoice.status())
        || outstanding.signum() <= 0
        || received.compareTo(outstanding) > 0)
      throw BusinessException.conflict("The invoice no longer matches this bank transaction.");
    Instant now = clock.instant();
    ledger.postPayment(
        organizationId, invoiceId, current.bankTransactionId(), bank.providerId(), received, now);
    BigDecimal remaining = ledger.receivableBalance(organizationId, invoiceId);
    invoices.settle(organizationId, invoiceId, invoice.total().subtract(remaining));
    jdbc.update(
        "UPDATE ledgerflow.reconciliation_cases SET status='MATCHED',matched_invoice_id=?,matched_amount=?,version=version+1,updated_at=? WHERE id=?",
        invoiceId,
        received,
        Timestamp.from(now),
        id);
    decision(organizationId, id, "MATCHED", invoiceId, null, received, actor, now);
    return get(organizationId, actor, id);
  }

  @Transactional
  Detail matchPayout(UUID organizationId, UUID actor, UUID id, UUID payoutId, long version) {
    access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
    ReconciliationCase current = requireCase(organizationId, id, true);
    requireOpenVersion(current, version);
    if (jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.payout_reconciliation_candidates WHERE organization_id=? AND case_id=? AND payout_id=?",
            Integer.class,
            organizationId,
            id,
            payoutId)
        != 1) throw BusinessException.invalid("Choose one of the current payout candidates.");
    var bank =
        jdbc
            .query(
                "SELECT provider_transaction_id,amount,currency,transaction_date,pending,removed_at FROM ledgerflow.bank_transactions WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, row) ->
                    new BankSnapshot(
                        rs.getString("provider_transaction_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("transaction_date", LocalDate.class),
                        rs.getBoolean("pending"),
                        rs.getTimestamp("removed_at") != null),
                organizationId,
                current.bankTransactionId())
            .stream()
            .findFirst()
            .orElseThrow(BusinessException::notFound);
    var payout =
        jdbc
            .query(
                "SELECT amount,currency,arrival_date FROM ledgerflow.stripe_payouts WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, row) ->
                    new PayoutSnapshot(
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("arrival_date", LocalDate.class)),
                organizationId,
                payoutId)
            .stream()
            .findFirst()
            .orElseThrow(BusinessException::notFound);
    BigDecimal received = bank.amount().abs();
    boolean validDate =
        !bank.transactionDate().isBefore(payout.arrivalDate().minusDays(3))
            && !bank.transactionDate().isAfter(payout.arrivalDate().plusDays(7));
    if (bank.pending()
        || bank.removed()
        || bank.amount().signum() >= 0
        || !"USD".equals(bank.currency())
        || !payout.currency().equals(bank.currency())
        || received.compareTo(payout.amount()) != 0
        || received.remainder(new BigDecimal("0.01")).signum() != 0
        || !validDate
        || payoutLedger.transitBalance(organizationId, payoutId).compareTo(payout.amount()) != 0)
      throw BusinessException.conflict("The payout no longer matches this bank transaction.");
    Instant now = clock.instant();
    payoutLedger.post(
        organizationId, payoutId, current.bankTransactionId(), bank.providerId(), received, now);
    jdbc.update(
        "UPDATE ledgerflow.reconciliation_cases SET status='MATCHED',matched_payout_id=?,matched_amount=?,version=version+1,updated_at=? WHERE id=?",
        payoutId,
        received,
        Timestamp.from(now),
        id);
    decision(organizationId, id, "MATCHED", null, payoutId, received, actor, now);
    return get(organizationId, actor, id);
  }

  @Transactional
  Detail ignore(UUID organizationId, UUID actor, UUID id, long version) {
    access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
    ReconciliationCase current = requireCase(organizationId, id, true);
    requireOpenVersion(current, version);
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE ledgerflow.reconciliation_cases SET status='IGNORED',version=version+1,updated_at=? WHERE id=?",
        Timestamp.from(now),
        id);
    decision(organizationId, id, "IGNORED", null, null, null, actor, now);
    return get(organizationId, actor, id);
  }

  private void decision(
      UUID organizationId,
      UUID caseId,
      String value,
      UUID invoiceId,
      UUID payoutId,
      BigDecimal amount,
      UUID actor,
      Instant now) {
    jdbc.update(
        "INSERT INTO ledgerflow.reconciliation_decisions(id,organization_id,case_id,decision,invoice_id,payout_id,amount,decided_by,decided_at) VALUES (?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        organizationId,
        caseId,
        value,
        invoiceId,
        payoutId,
        amount,
        actor,
        Timestamp.from(now));
  }

  private ReconciliationCase requireCase(UUID organizationId, UUID id, boolean lock) {
    return jdbc
        .query(
            CASE_SELECT
                + " WHERE c.organization_id=? AND c.id=?"
                + (lock ? " FOR UPDATE OF c" : ""),
            this::reconciliationCase,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private ReconciliationCase reconciliationCase(ResultSet rs, int row) throws SQLException {
    int invoiceCandidates = rs.getInt("invoice_candidate_count");
    int payoutCandidates = rs.getInt("payout_candidate_count");
    int candidates = invoiceCandidates + payoutCandidates;
    return new ReconciliationCase(
        rs.getObject("id", UUID.class),
        rs.getObject("bank_transaction_id", UUID.class),
        rs.getString("bank_description"),
        rs.getBigDecimal("bank_amount"),
        rs.getString("currency"),
        rs.getObject("transaction_date", LocalDate.class),
        rs.getString("status"),
        rs.getObject("matched_invoice_id", UUID.class),
        rs.getObject("matched_payout_id", UUID.class),
        rs.getBigDecimal("matched_amount"),
        rs.getLong("version"),
        candidates,
        invoiceCandidates,
        payoutCandidates,
        reviewState(candidates),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private String reviewState(int candidateCount) {
    if (candidateCount == 0) return "NO_CANDIDATE";
    if (candidateCount == 1) return "SINGLE_CANDIDATE";
    return "MULTIPLE_CANDIDATES";
  }

  private void requireOpenVersion(ReconciliationCase value, long version) {
    if (!"OPEN".equals(value.status()))
      throw BusinessException.conflict("This reconciliation case is already decided.");
    if (value.version() != version)
      throw BusinessException.conflict("This reconciliation case changed. Reload and try again.");
  }

  private record BankSnapshot(
      String providerId,
      BigDecimal amount,
      String currency,
      LocalDate transactionDate,
      boolean pending,
      boolean removed) {}

  private record PayoutSnapshot(BigDecimal amount, String currency, LocalDate arrivalDate) {}
}
