package com.ledgerflow.banking;

import com.ledgerflow.shared.api.BusinessException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class PlaidWebhookVerifier {
  private final PlaidGateway plaid;
  private final Clock clock;
  private final ConcurrentHashMap<String, PlaidGateway.VerificationKey> keys =
      new ConcurrentHashMap<>();

  PlaidWebhookVerifier(PlaidGateway plaid, Clock clock) {
    this.plaid = plaid;
    this.clock = clock;
  }

  void verify(String compactJwt, byte[] body) {
    try {
      SignedJWT jwt = SignedJWT.parse(compactJwt);
      if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) invalid();
      String id = jwt.getHeader().getKeyID();
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) invalid();
      PlaidGateway.VerificationKey key = keys.computeIfAbsent(id, plaid::verificationKey);
      long now = clock.instant().getEpochSecond();
      if (!"ES256".equals(key.algorithm())
          || !"P-256".equals(key.curve())
          || !"EC".equals(key.keyType())
          || !"sig".equals(key.use())
          || !id.equals(key.id())
          || key.createdAt() > now
          || (key.expiredAt() != null && key.expiredAt() <= now)) invalid();
      ECKey ec =
          new ECKey.Builder(Curve.P_256, new Base64URL(key.x()), new Base64URL(key.y()))
              .keyID(id)
              .build();
      if (!jwt.verify(new ECDSAVerifier(ec))) invalid();
      var issued = jwt.getJWTClaimsSet().getIssueTime();
      if (issued == null) invalid();
      long issuedAt = issued.toInstant().getEpochSecond();
      if (issuedAt < now - 300 || issuedAt > now + 30) invalid();
      String claimed = jwt.getJWTClaimsSet().getStringClaim("request_body_sha256");
      String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      if (claimed == null
          || !MessageDigest.isEqual(
              claimed.getBytes(StandardCharsets.US_ASCII),
              actual.getBytes(StandardCharsets.US_ASCII))) invalid();
      if (keys.size() > 16) keys.clear();
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      invalid();
    }
  }

  private void invalid() {
    throw BusinessException.invalid("Plaid webhook verification failed.");
  }
}
