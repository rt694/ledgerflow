package com.ledgerflow.banking;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PlaidSettingsTest {
  private static final String KEY =
      Base64.getEncoder()
          .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  @Test
  void enabledModeOnlyAcceptsSandboxAndSafeStorageSettings() {
    assertThatCode(
            () ->
                new PlaidSettings(
                    true, "client", "sandbox-secret", "https://example.test/webhook", KEY))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new PlaidSettings(true, "client", "", "https://x.test", KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PlaidSettings(true, "client", "sandbox-secret", "http://x.test", KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PlaidSettings(true, "client", "sandbox-secret", "https://x.test", "bad"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledModeNeedsNoCredentials() {
    assertThatCode(() -> new PlaidSettings(false, "", "", "", "")).doesNotThrowAnyException();
  }
}
