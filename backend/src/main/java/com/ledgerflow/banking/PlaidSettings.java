package com.ledgerflow.banking;

import com.ledgerflow.shared.api.BusinessException;
import java.net.URI;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class PlaidSettings {
  final boolean enabled;
  final String clientId;
  final String secret;
  final String webhookUrl;
  final byte[] encryptionKey;

  PlaidSettings(
      @Value("${ledgerflow.plaid.enabled:false}") boolean enabled,
      @Value("${ledgerflow.plaid.client-id:}") String clientId,
      @Value("${ledgerflow.plaid.secret:}") String secret,
      @Value("${ledgerflow.plaid.webhook-url:}") String webhookUrl,
      @Value("${ledgerflow.plaid.token-encryption-key:}") String encodedKey) {
    byte[] decoded = decode(encodedKey);
    if (enabled
        && (clientId.isBlank() || secret.isBlank() || decoded.length != 32 || !https(webhookUrl)))
      throw new IllegalArgumentException(
          "Plaid Sandbox requires credentials, a 32-byte encryption key, and an HTTPS webhook URL");
    this.enabled = enabled;
    this.clientId = clientId;
    this.secret = secret;
    this.webhookUrl = webhookUrl;
    this.encryptionKey = decoded;
  }

  void requireEnabled() {
    if (!enabled) throw unavailable();
  }

  static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "BANKING_UNAVAILABLE",
        "Sandbox banking is temporarily unavailable.");
  }

  private static byte[] decode(String value) {
    if (value.isBlank()) return new byte[0];
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Plaid token encryption key must be Base64", exception);
    }
  }

  private static boolean https(String value) {
    try {
      return "https".equalsIgnoreCase(URI.create(value).getScheme());
    } catch (RuntimeException exception) {
      return false;
    }
  }
}
