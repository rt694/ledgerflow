package com.ledgerflow.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvoiceCalculatorTest {
  InvoiceCalculator.LineInput line(int quantity, String price, String rate) {
    return new InvoiceCalculator.LineInput(
        "Synthetic item", quantity, new BigDecimal(price), new BigDecimal(rate));
  }

  @Test
  void multipliesAndSumsUsingExactDecimals() {
    var result =
        InvoiceCalculator.calculate(List.of(line(3, "19.99", "0.0825"), line(2, "0.10", "0")));
    assertThat(result.subtotal()).isEqualByComparingTo("60.17");
    assertThat(result.tax()).isEqualByComparingTo("4.95");
    assertThat(result.total()).isEqualByComparingTo("65.12");
  }

  @Test
  void roundsHalfUpPerLineRatherThanOnTheAggregate() {
    var result =
        InvoiceCalculator.calculate(List.of(line(1, "0.05", "0.1"), line(1, "0.05", "0.1")));
    assertThat(result.tax()).isEqualByComparingTo("0.02");
    assertThat(result.total()).isEqualByComparingTo("0.12");
  }

  @Test
  void totalsKeepTwoDecimalPlacesWithoutTax() {
    var result = InvoiceCalculator.calculate(List.of(line(1, "10", "0")));
    assertThat(result.total()).isEqualTo(new BigDecimal("10.00"));
    assertThat(result.tax()).isEqualTo(new BigDecimal("0.00"));
  }

  @Test
  void unexpectedFractionalCentsAreNotSilentlyRounded() {
    assertThatThrownBy(() -> InvoiceCalculator.calculate(List.of(line(1, "0.001", "0"))))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void largeSupportedValuesStayExact() {
    var result =
        InvoiceCalculator.calculate(
            java.util.Collections.nCopies(100, line(10000, "999999999.99", "1")));
    assertThat(result.total()).isEqualByComparingTo("1999999999980000.00");
  }
}
