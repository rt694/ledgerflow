package com.ledgerflow.invoicing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Prices and totals use decimal arithmetic. Tax is rounded per line, then summed. */
public final class InvoiceCalculator {
  private InvoiceCalculator() {}

  public static Totals calculate(List<LineInput> lines) {
    BigDecimal subtotal = new BigDecimal("0.00");
    BigDecimal tax = new BigDecimal("0.00");
    for (LineInput line : lines) {
      var result = lineTotals(line);
      subtotal = subtotal.add(result.subtotal());
      tax = tax.add(result.tax());
    }
    return new Totals(subtotal, tax, subtotal.add(tax));
  }

  public static Totals lineTotals(LineInput line) {
    BigDecimal subtotal =
        line.unitPrice()
            .multiply(BigDecimal.valueOf(line.quantity()))
            .setScale(2, RoundingMode.UNNECESSARY);
    BigDecimal tax = subtotal.multiply(line.taxRate()).setScale(2, RoundingMode.HALF_UP);
    return new Totals(subtotal, tax, subtotal.add(tax));
  }

  public record LineInput(
      @NotBlank @Size(max = 500) String description,
      @NotNull @Min(1) @Max(10000) Integer quantity,
      @NotNull @DecimalMin("0.00") @Digits(integer = 9, fraction = 2) BigDecimal unitPrice,
      @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4)
          BigDecimal taxRate) {}

  public record Totals(BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}
}
