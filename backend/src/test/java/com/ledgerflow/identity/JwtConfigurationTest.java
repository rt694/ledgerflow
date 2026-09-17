package com.ledgerflow.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class JwtConfigurationTest {
  KeyPair pair(int size) throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(size);
    return generator.generateKeyPair();
  }

  ByteArrayResource pem(String type, byte[] bytes) {
    String text =
        "-----BEGIN "
            + type
            + "-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
            + "\n-----END "
            + type
            + "-----\n";
    return new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  @Test
  void rejectsWeakPublicKeys() throws Exception {
    var weak = pair(512);
    assertThatThrownBy(
            () ->
                new JwtConfiguration()
                    .jwtPublicKey(pem("PUBLIC KEY", weak.getPublic().getEncoded())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsMismatchedSigningKeys() throws Exception {
    var first = pair(2048);
    var second = pair(2048);
    assertThatThrownBy(
            () ->
                new JwtConfiguration()
                    .jwtEncoder(
                        (RSAPublicKey) first.getPublic(),
                        pem("PRIVATE KEY", second.getPrivate().getEncoded())))
        .isInstanceOf(IllegalStateException.class);
  }
}
