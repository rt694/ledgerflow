package com.ledgerflow.invoicing;

import com.ledgerflow.customer.CustomerDirectory.CustomerSnapshot;
import com.ledgerflow.shared.api.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices", schema = "ledgerflow")
class Invoice {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "customer_id", nullable = false)
  private UUID customerId;

  @Column(name = "customer_name", nullable = false, length = 200)
  private String customerName;

  @Column(name = "customer_email", nullable = false, length = 254)
  private String customerEmail;

  @Column(name = "invoice_number", nullable = false, length = 64)
  private String number;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 3)
  private CurrencyCode currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InvoiceStatus status;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal subtotal;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal tax;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal total;

  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "issued_at")
  private Instant issuedAt;

  @Column(name = "voided_at")
  private Instant voidedAt;

  @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("position ASC")
  private List<InvoiceLine> lines = new ArrayList<>();

  protected Invoice() {}

  Invoice(
      UUID org,
      CustomerSnapshot customer,
      String number,
      CurrencyCode currency,
      LocalDate dueDate,
      List<InvoiceCalculator.LineInput> input,
      Instant now) {
    id = UUID.randomUUID();
    organizationId = org;
    status = InvoiceStatus.DRAFT;
    createdAt = now;
    apply(customer, number, currency, dueDate, input);
  }

  void update(
      CustomerSnapshot customer,
      String number,
      CurrencyCode currency,
      LocalDate dueDate,
      List<InvoiceCalculator.LineInput> input) {
    if (status != InvoiceStatus.DRAFT)
      throw BusinessException.conflict("Only draft invoices can be edited.");
    apply(customer, number, currency, dueDate, input);
    version++;
  }

  private void apply(
      CustomerSnapshot customer,
      String number,
      CurrencyCode currency,
      LocalDate dueDate,
      List<InvoiceCalculator.LineInput> input) {
    snapshot(customer);
    this.number = number;
    this.currency = currency;
    this.dueDate = dueDate;
    var totals = InvoiceCalculator.calculate(input);
    subtotal = totals.subtotal();
    tax = totals.tax();
    total = totals.total();
    lines.clear();
    for (int index = 0; index < input.size(); index++)
      lines.add(new InvoiceLine(this, index, input.get(index)));
  }

  private void snapshot(CustomerSnapshot customer) {
    customerId = customer.id();
    customerName = customer.name();
    customerEmail = customer.email();
  }

  void issue(CustomerSnapshot customer, Instant now) {
    if (status != InvoiceStatus.DRAFT)
      throw BusinessException.conflict("Only draft invoices can be issued.");
    snapshot(customer);
    status = InvoiceStatus.ISSUED;
    issuedAt = now;
    version++;
  }

  void voidInvoice(Instant now) {
    if (status == InvoiceStatus.VOID) throw BusinessException.conflict("Invoice is already void.");
    status = InvoiceStatus.VOID;
    voidedAt = now;
    version++;
  }

  UUID id() {
    return id;
  }

  UUID organizationId() {
    return organizationId;
  }

  UUID customerId() {
    return customerId;
  }

  String customerName() {
    return customerName;
  }

  String customerEmail() {
    return customerEmail;
  }

  String number() {
    return number;
  }

  CurrencyCode currency() {
    return currency;
  }

  InvoiceStatus status() {
    return status;
  }

  LocalDate dueDate() {
    return dueDate;
  }

  BigDecimal subtotal() {
    return subtotal;
  }

  BigDecimal tax() {
    return tax;
  }

  BigDecimal total() {
    return total;
  }

  long version() {
    return version;
  }

  Instant issuedAt() {
    return issuedAt;
  }

  Instant voidedAt() {
    return voidedAt;
  }

  List<InvoiceLine> lines() {
    return lines;
  }
}
