package com.ledgerflow.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StripeSettingsTest {
  @Test
  void rejectsLiveKeysEvenWhenDisabled() {
    assertThatThrownBy(() -> new StripeSettings(false, "sk_live_example", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void enabledRequiresBothSecrets() {
    assertThatThrownBy(() -> new StripeSettings(true, "sk_test_example", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledDoesNotPretendPaymentsWork() {
    assertThatThrownBy(() -> new StripeSettings(false, "", "").requireEnabled())
        .isInstanceOf(com.ledgerflow.shared.api.BusinessException.class);
  }
}
