package com.ledgerflow.ledger;

import com.ledgerflow.organization.OrganizationAccess;
import com.ledgerflow.shared.api.BusinessException;
import com.ledgerflow.shared.api.PageResponse;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/ledger")
@Transactional(readOnly = true)
public class LedgerController {
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;

  public LedgerController(JdbcTemplate jdbc, OrganizationAccess access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  @GetMapping("/accounts")
  public List<AccountBalance> accounts(
      @PathVariable UUID organizationId, @AuthenticationPrincipal Jwt jwt) {
    access.require(organizationId, UUID.fromString(jwt.getSubject()));
    return jdbc.query(
        "SELECT a.code,a.currency,coalesce(sum(e.debit),0) debits,coalesce(sum(e.credit),0) credits FROM ledgerflow.ledger_accounts a LEFT JOIN ledgerflow.journal_entries e ON e.organization_id=a.organization_id AND e.account_code=a.code AND e.currency=a.currency WHERE a.organization_id=? GROUP BY a.code,a.currency ORDER BY a.code",
        (rs, n) ->
            new AccountBalance(
                rs.getString("code"),
                rs.getString("currency"),
                rs.getBigDecimal("debits"),
                rs.getBigDecimal("credits"),
                rs.getBigDecimal("debits").subtract(rs.getBigDecimal("credits"))),
        organizationId);
  }

  @GetMapping("/journals")
  public PageResponse<Journal> journals(
      @PathVariable UUID organizationId,
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    access.require(organizationId, UUID.fromString(jwt.getSubject()));
    var items =
        jdbc.query(
            "SELECT * FROM ledgerflow.journal_transactions WHERE organization_id=? ORDER BY posted_at,id LIMIT ? OFFSET ?",
            (rs, n) ->
                new Journal(
                    rs.getObject("id", UUID.class),
                    rs.getObject("invoice_id", UUID.class),
                    rs.getString("operation"),
                    rs.getString("currency"),
                    rs.getTimestamp("posted_at").toInstant(),
                    rs.getObject("reverses_id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("source_key")),
            organizationId,
            size,
            (long) page * size);
    long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.journal_transactions WHERE organization_id=?",
            Long.class,
            organizationId);
    return new PageResponse<>(items, page, size, count);
  }

  @GetMapping("/journals/{id}")
  public JournalDetail journal(
      @PathVariable UUID organizationId, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    access.require(organizationId, UUID.fromString(jwt.getSubject()));
    var headers =
        jdbc.query(
            "SELECT * FROM ledgerflow.journal_transactions WHERE organization_id=? AND id=?",
            (rs, n) ->
                new Journal(
                    rs.getObject("id", UUID.class),
                    rs.getObject("invoice_id", UUID.class),
                    rs.getString("operation"),
                    rs.getString("currency"),
                    rs.getTimestamp("posted_at").toInstant(),
                    rs.getObject("reverses_id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("source_key")),
            organizationId,
            id);
    if (headers.isEmpty()) throw BusinessException.notFound();
    var entries =
        jdbc.query(
            "SELECT account_code,debit,credit FROM ledgerflow.journal_entries WHERE organization_id=? AND journal_id=? ORDER BY account_code",
            (rs, n) ->
                new Entry(
                    rs.getString("account_code"),
                    rs.getBigDecimal("debit"),
                    rs.getBigDecimal("credit")),
            organizationId,
            id);
    return new JournalDetail(headers.getFirst(), entries);
  }

  public record AccountBalance(
      String code,
      String currency,
      BigDecimal debits,
      BigDecimal credits,
      BigDecimal debitMinusCredit) {}

  public record Journal(
      UUID id,
      UUID invoiceId,
      String operation,
      String currency,
      Instant postedAt,
      UUID reversesId,
      UUID paymentId,
      String sourceKey) {}

  public record Entry(String accountCode, BigDecimal debit, BigDecimal credit) {}

  public record JournalDetail(Journal journal, List<Entry> entries) {}
}
