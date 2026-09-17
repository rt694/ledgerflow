package com.ledgerflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomersAndInvoicesIT extends IntegrationTestSupport {
  TestUser owner;
  TestUser employee;
  TestUser accountant;
  TestUser outsider;
  String org;
  String customer;
  String otherOrg;
  String otherCustomer;
  @Autowired JdbcTemplate jdbc;

  @BeforeAll
  void users() throws Exception {
    owner = register("invoiceOwner");
    employee = register("invoiceEmployee");
    accountant = register("invoiceAccountant");
    outsider = register("invoiceOutsider");
    otherOrg = organization(outsider);
    otherCustomer = createCustomer(otherOrg, outsider.token(), "Other customer").get("id").asText();
    addMember(otherOrg, outsider, owner, "EMPLOYEE");
  }

  @BeforeEach
  void setup() throws Exception {
    org = organization(owner);
    addMember(org, owner, employee, "EMPLOYEE");
    addMember(org, owner, accountant, "ACCOUNTANT");
    customer = createCustomer(org, owner.token(), "Original customer").get("id").asText();
  }

  JsonNode createCustomer(String organization, String token, String name) throws Exception {
    return send(
        POST,
        base(organization) + "/customers",
        token,
        Map.of("name", name, "email", "customer@example.test"),
        201);
  }

  Map<String, Object> line(int quantity, String price, String tax) {
    return Map.of(
        "description",
        "Synthetic service",
        "quantity",
        quantity,
        "unitPrice",
        price,
        "taxRate",
        tax);
  }

  Map<String, Object> invoiceBody(String customerId, String number, List<?> lines) {
    return new HashMap<>(
        Map.of(
            "customerId",
            customerId,
            "number",
            number,
            "currency",
            "USD",
            "dueDate",
            LocalDate.now().plusDays(30).toString(),
            "lines",
            lines));
  }

  JsonNode createInvoice(String token) throws Exception {
    return send(
        POST,
        base(org) + "/invoices",
        token,
        invoiceBody(customer, "INV-001", List.of(line(3, "19.99", "0.0825"))),
        201);
  }

  String path(JsonNode invoice) {
    return base(org) + "/invoices/" + invoice.get("id").asText();
  }

  Map<String, Object> editBody(JsonNode invoice) {
    var body =
        invoiceBody(customer, "INV-001", List.of(line(1, "0.05", "0.1"), line(1, "0.05", "0.1")));
    body.put("version", invoice.get("version").asLong());
    return body;
  }

  @Test
  void customerCreationReadsAndUpdatesAreOrganizationScoped() throws Exception {
    var result = send(GET, base(org) + "/customers/" + customer, employee.token(), null, 200);
    assertThat(result.get("name").asText()).isEqualTo("Original customer");
    var update =
        send(
            PUT,
            base(org) + "/customers/" + customer,
            employee.token(),
            Map.of("name", "Updated", "email", "UPPER@example.test", "version", 0),
            200);
    assertThat(update.get("version").asLong()).isEqualTo(1);
    assertThat(update.get("email").asText()).isEqualTo("upper@example.test");
    send(
        PUT,
        base(org) + "/customers/" + customer,
        owner.token(),
        Map.of("name", "Stale", "email", "test@example.test", "version", 0),
        409);
    send(GET, base(otherOrg) + "/customers/" + customer, owner.token(), null, 404);
    send(GET, base(org) + "/customers/" + otherCustomer, owner.token(), null, 404);
    send(
        PUT,
        base(otherOrg) + "/customers/" + customer,
        owner.token(),
        Map.of("name", "Denied", "email", "test@example.test", "version", 1),
        404);
    send(
        PUT,
        base(org) + "/customers/" + otherCustomer,
        owner.token(),
        Map.of("name", "Denied", "email", "test@example.test", "version", 0),
        404);
  }

  @Test
  void accountantCanReadCustomersButCannotCreateOrEditThem() throws Exception {
    send(GET, base(org) + "/customers", accountant.token(), null, 200);
    send(
        POST,
        base(org) + "/customers",
        accountant.token(),
        Map.of("name", "Denied", "email", "test@example.test"),
        403);
    send(
        PUT,
        base(org) + "/customers/" + customer,
        accountant.token(),
        Map.of("name", "Denied", "email", "test@example.test", "version", 0),
        403);
  }

  @Test
  void customerFieldsAndPaginationAreValidated() throws Exception {
    send(
        POST,
        base(org) + "/customers",
        owner.token(),
        Map.of("name", " ", "email", "invalid"),
        400);
    send(GET, base(org) + "/customers?size=101", owner.token(), null, 400);
  }

  @Test
  void draftCalculatesSafeAmountsAndPersistsLines() throws Exception {
    var invoice = createInvoice(employee.token());
    assertThat(invoice.get("status").asText()).isEqualTo("DRAFT");
    assertThat(invoice.get("currency").asText()).isEqualTo("USD");
    assertThat(invoice.get("subtotal").decimalValue()).isEqualByComparingTo("59.97");
    assertThat(invoice.get("tax").decimalValue()).isEqualByComparingTo("4.95");
    assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("64.92");
    var persisted = send(GET, path(invoice), accountant.token(), null, 200);
    assertThat(persisted.get("lines").size()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.invoice_lines WHERE invoice_id = ?",
                Integer.class,
                UUID.fromString(invoice.get("id").asText())))
        .isEqualTo(1);
  }

  @Test
  void draftEditReplacesLinesAndIncrementsVersion() throws Exception {
    var invoice = createInvoice(owner.token());
    var updated = send(PUT, path(invoice), employee.token(), editBody(invoice), 200);
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    assertThat(updated.get("tax").decimalValue()).isEqualByComparingTo("0.02");
    assertThat(updated.get("lines").size()).isEqualTo(2);
    var read = send(GET, path(invoice), owner.token(), null, 200);
    assertThat(read.get("lines").size()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM ledgerflow.invoice_lines WHERE invoice_id = ?",
                Integer.class,
                UUID.fromString(invoice.get("id").asText())))
        .isEqualTo(2);
    send(PUT, path(invoice), owner.token(), editBody(invoice), 409);
  }

  @Test
  void accountantCanIssueAndVoidWhileEmployeeCannot() throws Exception {
    var invoice = createInvoice(accountant.token());
    send(POST, path(invoice) + "/issue", employee.token(), Map.of("version", 0), 403);
    var issued =
        send(POST, path(invoice) + "/issue", accountant.token(), Map.of("version", 0), 200);
    assertThat(issued.get("status").asText()).isEqualTo("ISSUED");
    assertThat(issued.get("issuedAt").isNull()).isFalse();
    assertThat(issued.get("version").asLong()).isEqualTo(1);
    send(POST, path(invoice) + "/void", employee.token(), Map.of("version", 1), 403);
    var voided = send(POST, path(invoice) + "/void", accountant.token(), Map.of("version", 1), 200);
    assertThat(voided.get("status").asText()).isEqualTo("VOID");
    assertThat(voided.get("voidedAt").isNull()).isFalse();
  }

  @Test
  void issuedInvoiceCannotBeEditedOrIssuedAgain() throws Exception {
    var invoice = createInvoice(owner.token());
    var issued = send(POST, path(invoice) + "/issue", owner.token(), Map.of("version", 0), 200);
    send(PUT, path(invoice), owner.token(), editBody(issued), 409);
    send(POST, path(invoice) + "/issue", owner.token(), Map.of("version", 1), 409);
    assertThat(send(GET, path(invoice), owner.token(), null, 200).get("total").decimalValue())
        .isEqualByComparingTo("64.92");
  }

  @Test
  void voidIsTerminalAndDraftCanBeVoided() throws Exception {
    var invoice = createInvoice(owner.token());
    var voided = send(POST, path(invoice) + "/void", owner.token(), Map.of("version", 0), 200);
    send(PUT, path(invoice), owner.token(), editBody(voided), 409);
    send(POST, path(invoice) + "/issue", owner.token(), Map.of("version", 1), 409);
    send(POST, path(invoice) + "/void", owner.token(), Map.of("version", 1), 409);
  }

  @Test
  void issuedCustomerSnapshotSurvivesLaterCustomerEdits() throws Exception {
    var invoice = createInvoice(owner.token());
    send(
        PUT,
        base(org) + "/customers/" + customer,
        owner.token(),
        Map.of("name", "At issue", "email", "issue@example.test", "version", 0),
        200);
    var issued = send(POST, path(invoice) + "/issue", owner.token(), Map.of("version", 0), 200);
    assertThat(issued.get("customerName").asText()).isEqualTo("At issue");
    send(
        PUT,
        base(org) + "/customers/" + customer,
        owner.token(),
        Map.of("name", "Later name", "email", "later@example.test", "version", 1),
        200);
    var read = send(GET, path(invoice), owner.token(), null, 200);
    assertThat(read.get("customerName").asText()).isEqualTo("At issue");
    assertThat(read.get("customerEmail").asText()).isEqualTo("issue@example.test");
  }

  @Test
  void foreignInvoiceCannotBeReadEditedOrTransitionedEvenWithBothMemberships() throws Exception {
    var invoice = createInvoice(owner.token());
    String wrong = base(otherOrg) + "/invoices/" + invoice.get("id").asText();
    send(GET, wrong, owner.token(), null, 404);
    send(PUT, wrong, owner.token(), editBody(invoice), 404);
    send(POST, wrong + "/issue", outsider.token(), Map.of("version", 0), 404);
    send(POST, wrong + "/void", outsider.token(), Map.of("version", 0), 404);
    send(GET, path(invoice), outsider.token(), null, 404);
  }

  @Test
  void foreignCustomerIsRejectedWithoutPartialInvoiceCreation() throws Exception {
    send(
        POST,
        base(org) + "/invoices",
        owner.token(),
        invoiceBody(otherCustomer, "INV-FOREIGN", List.of(line(1, "1.00", "0"))),
        404);
    assertThat(
            send(GET, base(org) + "/invoices", owner.token(), null, 200)
                .get("totalElements")
                .asLong())
        .isZero();
  }

  @Test
  void failedDraftEditLeavesExistingInvoiceAndLinesIntact() throws Exception {
    var invoice = createInvoice(owner.token());
    var body = editBody(invoice);
    body.put("customerId", otherCustomer);
    send(PUT, path(invoice), owner.token(), body, 404);
    var read = send(GET, path(invoice), owner.token(), null, 200);
    assertThat(read.get("version").asLong()).isZero();
    assertThat(read.get("lines").size()).isEqualTo(1);
    assertThat(read.get("total").decimalValue()).isEqualByComparingTo("64.92");
  }

  @Test
  void databaseConflictRollsBackEditedAmountsAndReplacementLines() throws Exception {
    var invoice = createInvoice(owner.token());
    send(
        POST,
        base(org) + "/invoices",
        owner.token(),
        invoiceBody(customer, "INV-002", List.of(line(1, "1.00", "0"))),
        201);
    var body = editBody(invoice);
    body.put("number", "INV-002");
    send(PUT, path(invoice), owner.token(), body, 409);
    var read = send(GET, path(invoice), owner.token(), null, 200);
    assertThat(read.get("number").asText()).isEqualTo("INV-001");
    assertThat(read.get("version").asLong()).isZero();
    assertThat(read.get("lines").size()).isEqualTo(1);
    assertThat(read.get("total").decimalValue()).isEqualByComparingTo("64.92");
  }

  @Test
  void invoiceNumbersAreUniqueOnlyWithinAnOrganization() throws Exception {
    createInvoice(owner.token());
    send(
        POST,
        base(org) + "/invoices",
        owner.token(),
        invoiceBody(customer, "INV-001", List.of(line(1, "1.00", "0"))),
        409);
    send(
        POST,
        base(otherOrg) + "/invoices",
        outsider.token(),
        invoiceBody(otherCustomer, "INV-001", List.of(line(1, "1.00", "0"))),
        201);
  }

  @Test
  void inputsRejectUnsupportedCurrencyPastDatesAndBadAmounts() throws Exception {
    var body = invoiceBody(customer, "INVALID", List.of(line(1, "1.00", "0")));
    body.put("currency", "ZZZ");
    send(POST, base(org) + "/invoices", owner.token(), body, 400);
    body.put("currency", "USD");
    body.put("dueDate", LocalDate.now().minusDays(1).toString());
    send(POST, base(org) + "/invoices", owner.token(), body, 400);
    for (var invalidLine :
        List.of(
            line(0, "1.00", "0"),
            line(1, "-1.00", "0"),
            line(1, "0.001", "0"),
            line(1, "1.00", "1.01"),
            line(1, "1.00", "0.00001"))) {
      send(
          POST,
          base(org) + "/invoices",
          owner.token(),
          invoiceBody(customer, "INVALID", List.of(invalidLine)),
          400);
    }
    send(
        POST,
        base(org) + "/invoices",
        owner.token(),
        invoiceBody(customer, "INVALID", List.of()),
        400);
  }

  @Test
  void nullLinesAndMissingVersionsAreRejected() throws Exception {
    var body = invoiceBody(customer, "INVALID", Arrays.asList((Object) null));
    send(POST, base(org) + "/invoices", owner.token(), body, 400);
    var invoice = createInvoice(owner.token());
    send(POST, path(invoice) + "/issue", owner.token(), Map.of(), 400);
    send(GET, base(org) + "/invoices?page=-1", owner.token(), null, 400);
  }

  @Test
  void listContainsOnlySelectedOrganizationsInvoices() throws Exception {
    createInvoice(owner.token());
    var mine = send(GET, base(org) + "/invoices", employee.token(), null, 200);
    assertThat(mine.get("totalElements").asLong()).isEqualTo(1);
    var other = send(GET, base(otherOrg) + "/invoices", owner.token(), null, 200);
    // Other tests may have created invoices there, but none can reference this customer.
    for (JsonNode item : other.get("items"))
      assertThat(item.get("customerId").asText()).isNotEqualTo(customer);
  }

  @Test
  void databaseRejectsCrossOrganizationCustomerLinksAndInconsistentLineAmounts() throws Exception {
    var invoice = createInvoice(owner.token());
    UUID id = UUID.fromString(invoice.get("id").asText());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ledgerflow.invoices SET customer_id = ? WHERE id = ?",
                    UUID.fromString(otherCustomer),
                    id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ledgerflow.invoice_lines SET unit_price = 1 WHERE invoice_id = ?", id))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(send(GET, path(invoice), owner.token(), null, 200).get("customerId").asText())
        .isEqualTo(customer);
  }

  @Test
  void concurrentDraftEditsWithTheSameVersionAllowOnlyOneWinner() throws Exception {
    var invoice = createInvoice(owner.token());
    var edits = editBody(invoice);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    Callable<Integer> call =
        () -> {
          ready.countDown();
          assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
          var request =
              put(path(invoice))
                  .header("Authorization", "Bearer " + owner.token())
                  .contentType("application/json")
                  .content(json.writeValueAsString(edits));
          return mvc.perform(request).andReturn().getResponse().getStatus();
        };
    try {
      Future<Integer> first = pool.submit(call);
      Future<Integer> second = pool.submit(call);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(send(GET, path(invoice), owner.token(), null, 200).get("version").asLong())
          .isEqualTo(1);
    } finally {
      go.countDown();
      pool.shutdownNow();
    }
  }
}
