package com.ledgerflow.banking;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class TokenCipherTest {
  private static final String KEY =
      Base64.getEncoder()
          .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  @Test
  void roundTripsWithRandomAuthenticatedEncryption() {
    var cipher =
        new TokenCipher(
            new PlaidSettings(
                true, "client", "sandbox-secret", "https://example.test/webhook", KEY));
    UUID org = UUID.randomUUID(), connection = UUID.randomUUID();
    var first = cipher.encrypt("access-sandbox-sensitive", org, connection);
    var second = cipher.encrypt("access-sandbox-sensitive", org, connection);

    assertThat(first.iv()).isNotEqualTo(second.iv());
    assertThat(new String(first.cipher(), StandardCharsets.ISO_8859_1))
        .doesNotContain("access-sandbox-sensitive");
    assertThat(cipher.decrypt(first.cipher(), first.iv(), org, connection))
        .isEqualTo("access-sandbox-sensitive");
    assertThatThrownBy(
            () -> cipher.decrypt(first.cipher(), first.iv(), UUID.randomUUID(), connection))
        .isInstanceOf(IllegalStateException.class);
    first.cipher()[0] ^= 1;
    assertThatThrownBy(() -> cipher.decrypt(first.cipher(), first.iv(), org, connection))
        .isInstanceOf(IllegalStateException.class);
  }
}
