package com.ledgerflow.payment;

import com.ledgerflow.shared.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class StripeSettings {
  final boolean enabled;
  final String key;
  final String webhook;

  StripeSettings(
      @Value("${ledgerflow.stripe.enabled:false}") boolean enabled,
      @Value("${ledgerflow.stripe.secret-key:}") String key,
      @Value("${ledgerflow.stripe.webhook-secret:}") String webhook) {
    if (!key.isBlank() && !key.startsWith("sk_test_") && !key.startsWith("rk_test_"))
      throw new IllegalArgumentException("Only Stripe test keys are allowed");
    if (enabled && (key.isBlank() || !webhook.startsWith("whsec_")))
      throw new IllegalArgumentException(
          "Stripe Sandbox requires test credentials and a webhook secret");
    this.enabled = enabled;
    this.key = key;
    this.webhook = webhook;
  }

  void requireEnabled() {
    if (!enabled) throw unavailable();
  }

  static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PAYMENT_UNAVAILABLE",
        "Sandbox payments are unavailable. Retry with the same idempotency key.");
  }
}
