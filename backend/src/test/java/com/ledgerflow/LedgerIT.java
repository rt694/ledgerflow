package com.ledgerflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIT extends IntegrationTestSupport {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  record Fixture(TestUser owner, String org, String invoice) {}

  Fixture fixture(String price, String rate) throws Exception {
    var owner = register("ledger");
    var org = organization(owner);
    var customer =
        send(
                POST,
                base(org) + "/customers",
                owner.token(),
                Map.of("name", "Synthetic customer", "email", "customer@example.test"),
                201)
            .get("id")
            .asText();
    var invoice =
        send(
                POST,
                base(org) + "/invoices",
                owner.token(),
                Map.of(
                    "customerId",
                    customer,
                    "number",
                    "LEDGER-1",
                    "currency",
                    "USD",
                    "dueDate",
                    LocalDate.now().plusDays(30).toString(),
                    "lines",
                    List.of(
                        Map.of(
                            "description",
                            "Delivered work",
                            "quantity",
                            1,
                            "unitPrice",
                            price,
                            "taxRate",
                            rate))),
                201)
            .get("id")
            .asText();
    return new Fixture(owner, org, invoice);
  }

  void issue(Fixture f) throws Exception {
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/issue",
        f.owner().token(),
        Map.of("version", 0),
        200);
  }

  JsonNode journals(Fixture f) throws Exception {
    return send(GET, base(f.org()) + "/ledger/journals", f.owner().token(), null, 200);
  }

  @Test
  void postingAndReversalPreserveHistoryAndNetBalances() throws Exception {
    var f = fixture("100.00", "0.0825");
    assertThat(journals(f).get("totalElements").asInt()).isZero();
    issue(f);
    var original = journals(f).get("items").get(0);
    var detail =
        send(
            GET,
            base(f.org()) + "/ledger/journals/" + original.get("id").asText(),
            f.owner().token(),
            null,
            200);
    assertThat(detail.get("entries").size()).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(debit) FROM ledgerflow.journal_entries WHERE journal_id=?",
                BigDecimal.class,
                UUID.fromString(original.get("id").asText())))
        .isEqualByComparingTo("108.25");
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/void",
        f.owner().token(),
        Map.of("version", 1),
        200);
    var all = journals(f).get("items");
    assertThat(all.size()).isEqualTo(2);
    assertThat(all.get(1).get("reversesId").asText()).isEqualTo(original.get("id").asText());
    assertThat(
            send(
                GET,
                base(f.org()) + "/ledger/journals/" + original.get("id").asText(),
                f.owner().token(),
                null,
                200))
        .isEqualTo(detail);
    var balances = send(GET, base(f.org()) + "/ledger/accounts", f.owner().token(), null, 200);
    for (var balance : balances)
      assertThat(balance.get("debitMinusCredit").decimalValue()).isEqualByComparingTo("0");
  }

  @Test
  void zeroDollarAndDraftVoidsHaveNoPosting() throws Exception {
    var f = fixture("0.00", "0");
    issue(f);
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/void",
        f.owner().token(),
        Map.of("version", 1),
        200);
    assertThat(journals(f).get("totalElements").asInt()).isZero();
    var draft = fixture("5.00", "0");
    send(
        POST,
        base(draft.org()) + "/invoices/" + draft.invoice() + "/void",
        draft.owner().token(),
        Map.of("version", 0),
        200);
    assertThat(journals(draft).get("totalElements").asInt()).isZero();
  }

  @Test
  void committedLedgerCannotBeChangedOrExtended() throws Exception {
    var f = fixture("10.00", "0");
    issue(f);
    var id = UUID.fromString(journals(f).get("items").get(0).get("id").asText());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ledgerflow.journal_entries SET debit=11 WHERE journal_id=? AND debit>0",
                    id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM ledgerflow.journal_transactions WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM ledgerflow.journal_entries WHERE journal_id=?", id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO ledgerflow.journal_entries VALUES (?,?,?,'CASH','USD',1,0)",
                    UUID.randomUUID(),
                    UUID.fromString(f.org()),
                    id))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void databaseRejectsEmptyAndUnbalancedJournalsAtCommit() throws Exception {
    var f = fixture("10.00", "0");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at) VALUES (?,?,?,'ISSUE','EUR',now())",
                    UUID.randomUUID(),
                    UUID.fromString(f.org()),
                    UUID.fromString(f.invoice())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    for (boolean empty : List.of(true, false)) {
      assertThatThrownBy(
              () ->
                  new TransactionTemplate(transactions)
                      .execute(
                          status -> {
                            var id = UUID.randomUUID();
                            jdbc.update(
                                "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at) VALUES (?,?,?,'ISSUE','USD',now())",
                                id,
                                UUID.fromString(f.org()),
                                UUID.fromString(f.invoice()));
                            if (!empty)
                              jdbc.update(
                                  "INSERT INTO ledgerflow.journal_entries VALUES (?,?,?,'RECEIVABLES','USD',10,0)",
                                  UUID.randomUUID(),
                                  UUID.fromString(f.org()),
                                  id);
                            return null;
                          }))
          .isInstanceOf(RuntimeException.class);
      assertThat(journals(f).get("totalElements").asInt()).isZero();
    }
  }

  @Test
  void ledgerFailureRollsBackInvoiceState() throws Exception {
    var f = fixture("10.00", "0");
    // An existing source posting deliberately provokes the unique-source constraint.
    new TransactionTemplate(transactions)
        .execute(
            status -> {
              var id = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at) VALUES (?,?,?,'ISSUE','USD',now())",
                  id,
                  UUID.fromString(f.org()),
                  UUID.fromString(f.invoice()));
              jdbc.update(
                  "INSERT INTO ledgerflow.journal_entries VALUES (?,?,?,'RECEIVABLES','USD',10,0),(?,?,?,'REVENUE','USD',0,10)",
                  UUID.randomUUID(),
                  UUID.fromString(f.org()),
                  id,
                  UUID.randomUUID(),
                  UUID.fromString(f.org()),
                  id);
              return null;
            });
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/issue",
        f.owner().token(),
        Map.of("version", 0),
        409);
    var invoice =
        send(GET, base(f.org()) + "/invoices/" + f.invoice(), f.owner().token(), null, 200);
    assertThat(invoice.get("status").asText()).isEqualTo("DRAFT");
    assertThat(invoice.get("version").asInt()).isZero();
  }

  @Test
  void organizationAndRoleChecksApplyToLedger() throws Exception {
    var f = fixture("10.00", "0");
    issue(f);
    var outsider = register("outsider");
    send(GET, base(f.org()) + "/ledger/accounts", outsider.token(), null, 404);
    var other = organization(outsider);
    var id = journals(f).get("items").get(0).get("id").asText();
    send(GET, base(other) + "/ledger/journals/" + id, outsider.token(), null, 404);
    addMember(f.org(), f.owner(), outsider, "EMPLOYEE");
    send(GET, base(f.org()) + "/ledger/accounts", outsider.token(), null, 200);
    send(GET, base(f.org()) + "/ledger/journals?page=0&size=101", outsider.token(), null, 400);
    send(
        POST,
        base(f.org()) + "/invoices/" + f.invoice() + "/void",
        outsider.token(),
        Map.of("version", 1),
        403);
  }

  @Test
  void concurrentIssueAndVoidRequestsEachCreateOnlyOnePosting() throws Exception {
    var f = fixture("10.00", "0");
    for (String operation : List.of("issue", "void")) {
      int version = operation.equals("issue") ? 0 : 1;
      try (var pool = Executors.newFixedThreadPool(2)) {
        var gate = new CountDownLatch(1);
        Callable<Integer> action =
            () -> {
              gate.await();
              return mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                              base(f.org()) + "/invoices/" + f.invoice() + "/" + operation)
                          .header("Authorization", "Bearer " + f.owner().token())
                          .contentType("application/json")
                          .content("{\"version\":" + version + "}"))
                  .andReturn()
                  .getResponse()
                  .getStatus();
            };
        var first = pool.submit(action);
        var second = pool.submit(action);
        gate.countDown();
        assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(200, 409);
      }
      assertThat(journals(f).get("totalElements").asInt()).isEqualTo(version + 1);
    }
  }

  @Test
  void databaseRejectsWrongReversalAndForeignOrganizationEntries() throws Exception {
    var f = fixture("10.00", "0");
    issue(f);
    var original = UUID.fromString(journals(f).get("items").get(0).get("id").asText());
    var other = organization(f.owner());
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          var id = UUID.randomUUID();
                          jdbc.update(
                              "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at,reverses_id) VALUES (?,?,?,'VOID','USD',now(),?)",
                              id,
                              UUID.fromString(f.org()),
                              UUID.fromString(f.invoice()),
                              original);
                          jdbc.update(
                              "INSERT INTO ledgerflow.journal_entries VALUES (?,?,?,'RECEIVABLES','USD',0,9),(?,?,?,'REVENUE','USD',9,0)",
                              UUID.randomUUID(),
                              UUID.fromString(f.org()),
                              id,
                              UUID.randomUUID(),
                              UUID.fromString(f.org()),
                              id);
                          return null;
                        }))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          var id = UUID.randomUUID();
                          jdbc.update(
                              "INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at,reverses_id) VALUES (?,?,?,'VOID','USD',now(),?)",
                              id,
                              UUID.fromString(f.org()),
                              UUID.fromString(f.invoice()),
                              original);
                          jdbc.update(
                              "INSERT INTO ledgerflow.journal_entries VALUES (?,?,?,'RECEIVABLES','USD',0,10)",
                              UUID.randomUUID(),
                              UUID.fromString(other),
                              id);
                          return null;
                        }))
        .isInstanceOf(RuntimeException.class);
    assertThat(journals(f).get("totalElements").asInt()).isEqualTo(1);
  }
}
