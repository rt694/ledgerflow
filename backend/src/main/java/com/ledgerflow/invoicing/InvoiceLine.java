package com.ledgerflow.invoicing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_lines", schema = "ledgerflow")
class InvoiceLine {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invoice_id", nullable = false)
  private Invoice invoice;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "line_position", nullable = false)
  private int position;

  @Column(nullable = false, length = 500)
  private String description;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, precision = 11, scale = 2)
  private BigDecimal unitPrice;

  @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
  private BigDecimal taxRate;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal subtotal;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal tax;

  @Column(nullable = false, precision = 19, scale = 2)
  private BigDecimal total;

  protected InvoiceLine() {}

  InvoiceLine(Invoice invoice, int position, InvoiceCalculator.LineInput input) {
    id = UUID.randomUUID();
    this.invoice = invoice;
    organizationId = invoice.organizationId();
    this.position = position;
    description = input.description().strip();
    quantity = input.quantity();
    unitPrice = input.unitPrice();
    taxRate = input.taxRate();
    var totals = InvoiceCalculator.lineTotals(input);
    subtotal = totals.subtotal();
    tax = totals.tax();
    total = totals.total();
  }

  InvoiceController.LineResponse response() {
    return new InvoiceController.LineResponse(
        description, quantity, unitPrice, taxRate, subtotal, tax, total);
  }
}
