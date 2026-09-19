package com.ledgerflow.banking;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
class TokenCipher {
  private static final int IV_BYTES = 12;
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  TokenCipher(PlaidSettings settings) {
    this.key =
        new SecretKeySpec(
            settings.encryptionKey.length == 32 ? settings.encryptionKey : new byte[32], "AES");
  }

  record Sealed(byte[] cipher, byte[] iv) {}

  Sealed encrypt(String token, UUID organizationId, UUID connectionId) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      cipher.updateAAD(aad(organizationId, connectionId));
      return new Sealed(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), iv);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Could not protect the bank token", exception);
    }
  }

  String decrypt(byte[] encrypted, byte[] iv, UUID organizationId, UUID connectionId) {
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      cipher.updateAAD(aad(organizationId, connectionId));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Could not unlock the bank token", exception);
    }
  }

  private byte[] aad(UUID organizationId, UUID connectionId) {
    return ByteBuffer.allocate(32)
        .putLong(organizationId.getMostSignificantBits())
        .putLong(organizationId.getLeastSignificantBits())
        .putLong(connectionId.getMostSignificantBits())
        .putLong(connectionId.getLeastSignificantBits())
        .array();
  }
}
