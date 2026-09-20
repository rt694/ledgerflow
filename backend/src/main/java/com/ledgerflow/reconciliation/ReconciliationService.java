package com.ledgerflow.reconciliation;

import com.ledgerflow.invoicing.InvoicePaymentAccess;
import com.ledgerflow.ledger.BankReconciliationLedger;
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
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;
  private final InvoicePaymentAccess invoices;
  private final BankReconciliationLedger ledger;
  private final Clock clock;

  ReconciliationService(
      JdbcTemplate jdbc,
      OrganizationAccess access,
      InvoicePaymentAccess invoices,
      BankReconciliationLedger ledger,
      Clock clock) {
    this.jdbc = jdbc;
    this.access = access;
    this.invoices = invoices;
    this.ledger = ledger;
    this.clock = clock;
  }

  record RefreshResult(int newCases, int candidates) {}

  record ReconciliationCase(
      UUID id,
      UUID bankTransactionId,
      String bankDescription,
      BigDecimal bankAmount,
      String currency,
      LocalDate transactionDate,
      String status,
      UUID matchedInvoiceId,
      long version,
      int candidateCount,
      Instant createdAt,
      Instant updatedAt) {}

  record Candidate(
      UUID invoiceId,
      String invoiceNumber,
      BigDecimal invoiceTotal,
      LocalDate dueDate,
      BigDecimal score,
      String rule) {}

  record Decision(String decision, UUID invoiceId, UUID decidedBy, Instant decidedAt) {}

  record Detail(
      ReconciliationCase reconciliationCase,
      List<Candidate> candidates,
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
    int candidates =
        jdbc.update(
            "INSERT INTO ledgerflow.reconciliation_candidates(id,organization_id,case_id,invoice_id,score,rule,created_at) SELECT gen_random_uuid(),c.organization_id,c.id,i.id,CASE WHEN position(lower(i.invoice_number) in lower(bt.name))>0 THEN 1.0000 ELSE 0.8500 END,CASE WHEN position(lower(i.invoice_number) in lower(bt.name))>0 THEN 'REFERENCE_AND_EXACT_AMOUNT' ELSE 'EXACT_AMOUNT_DATE_WINDOW' END,? FROM ledgerflow.reconciliation_cases c JOIN ledgerflow.bank_transactions bt ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id JOIN ledgerflow.invoices i ON i.organization_id=c.organization_id AND i.status='ISSUED' AND i.currency=bt.currency AND i.total=abs(bt.amount) AND bt.transaction_date>=timezone('UTC',i.issued_at)::date AND bt.transaction_date<=i.due_date+30 WHERE c.organization_id=? AND c.status='OPEN'",
            Timestamp.from(now),
            organizationId);
    return new RefreshResult(cases, candidates);
  }

  @Transactional(readOnly = true)
  List<ReconciliationCase> list(UUID organizationId, UUID actor, String status) {
    access.require(organizationId, actor);
    String normalized = status == null ? null : status.toUpperCase(Locale.ROOT);
    if (normalized != null && !Set.of("OPEN", "MATCHED", "IGNORED").contains(normalized))
      throw BusinessException.invalid("Unknown reconciliation status.");
    return jdbc.query(
        "SELECT c.*,bt.name bank_description,bt.amount bank_amount,bt.currency,bt.transaction_date,(SELECT count(*) FROM ledgerflow.reconciliation_candidates x WHERE x.case_id=c.id) candidate_count FROM ledgerflow.reconciliation_cases c JOIN ledgerflow.bank_transactions bt ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id WHERE c.organization_id=? AND (?::varchar IS NULL OR c.status=?::varchar) ORDER BY c.created_at,c.id LIMIT 100",
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
            "SELECT rc.invoice_id,i.invoice_number,i.total,i.due_date,rc.score,rc.rule FROM ledgerflow.reconciliation_candidates rc JOIN ledgerflow.invoices i ON i.organization_id=rc.organization_id AND i.id=rc.invoice_id WHERE rc.organization_id=? AND rc.case_id=? ORDER BY rc.score DESC,i.due_date,i.id",
            (rs, row) ->
                new Candidate(
                    rs.getObject("invoice_id", UUID.class),
                    rs.getString("invoice_number"),
                    rs.getBigDecimal("total"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getBigDecimal("score"),
                    rs.getString("rule")),
            organizationId,
            id);
    var decisions =
        jdbc.query(
            "SELECT decision,invoice_id,decided_by,decided_at FROM ledgerflow.reconciliation_decisions WHERE organization_id=? AND case_id=? ORDER BY decided_at,id",
            (rs, row) ->
                new Decision(
                    rs.getString("decision"),
                    rs.getObject("invoice_id", UUID.class),
                    rs.getObject("decided_by", UUID.class),
                    rs.getTimestamp("decided_at").toInstant()),
            organizationId,
            id);
    return new Detail(value, candidates, decisions);
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
                "SELECT provider_transaction_id,amount,currency,pending,removed_at FROM ledgerflow.bank_transactions WHERE organization_id=? AND id=? FOR UPDATE",
                (rs, row) ->
                    new BankSnapshot(
                        rs.getString("provider_transaction_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
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
    if (!"ISSUED".equals(invoice.status()) || invoice.total().compareTo(received) != 0)
      throw BusinessException.conflict("The invoice no longer matches this bank transaction.");
    Instant now = clock.instant();
    ledger.postPayment(
        organizationId, invoiceId, current.bankTransactionId(), bank.providerId(), received, now);
    invoices.settle(organizationId, invoiceId, received);
    jdbc.update(
        "UPDATE ledgerflow.reconciliation_cases SET status='MATCHED',matched_invoice_id=?,version=version+1,updated_at=? WHERE id=?",
        invoiceId,
        Timestamp.from(now),
        id);
    decision(organizationId, id, "MATCHED", invoiceId, actor, now);
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
    decision(organizationId, id, "IGNORED", null, actor, now);
    return get(organizationId, actor, id);
  }

  private void decision(
      UUID organizationId, UUID caseId, String value, UUID invoiceId, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO ledgerflow.reconciliation_decisions(id,organization_id,case_id,decision,invoice_id,decided_by,decided_at) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        organizationId,
        caseId,
        value,
        invoiceId,
        actor,
        Timestamp.from(now));
  }

  private ReconciliationCase requireCase(UUID organizationId, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT c.*,bt.name bank_description,bt.amount bank_amount,bt.currency,bt.transaction_date,(SELECT count(*) FROM ledgerflow.reconciliation_candidates x WHERE x.case_id=c.id) candidate_count FROM ledgerflow.reconciliation_cases c JOIN ledgerflow.bank_transactions bt ON bt.organization_id=c.organization_id AND bt.id=c.bank_transaction_id WHERE c.organization_id=? AND c.id=?"
                + (lock ? " FOR UPDATE OF c" : ""),
            this::reconciliationCase,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private ReconciliationCase reconciliationCase(ResultSet rs, int row) throws SQLException {
    return new ReconciliationCase(
        rs.getObject("id", UUID.class),
        rs.getObject("bank_transaction_id", UUID.class),
        rs.getString("bank_description"),
        rs.getBigDecimal("bank_amount"),
        rs.getString("currency"),
        rs.getObject("transaction_date", LocalDate.class),
        rs.getString("status"),
        rs.getObject("matched_invoice_id", UUID.class),
        rs.getLong("version"),
        rs.getInt("candidate_count"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private void requireOpenVersion(ReconciliationCase value, long version) {
    if (!"OPEN".equals(value.status()))
      throw BusinessException.conflict("This reconciliation case is already decided.");
    if (value.version() != version)
      throw BusinessException.conflict("This reconciliation case changed. Reload and try again.");
  }

  private record BankSnapshot(
      String providerId, BigDecimal amount, String currency, boolean pending, boolean removed) {}
}
