package com.ledgerflow.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private static final Duration LIFETIME = Duration.ofMinutes(15);
  private final JwtEncoder encoder;
  private final Clock clock;
  private final String issuer;

  public TokenService(
      JwtEncoder encoder, Clock clock, @Value("${ledgerflow.security.jwt.issuer}") String issuer) {
    this.encoder = encoder;
    this.clock = clock;
    this.issuer = issuer;
  }

  TokenResponse issue(UUID userId) {
    var now = clock.instant();
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(userId.toString())
            .audience(List.of("ledgerflow-api"))
            .issuedAt(now)
            .expiresAt(now.plus(LIFETIME))
            .id(UUID.randomUUID().toString())
            .build();
    var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
    return new TokenResponse(
        encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(),
        "Bearer",
        LIFETIME.toSeconds(),
        userId);
  }

  public record TokenResponse(String accessToken, String tokenType, long expiresIn, UUID userId) {}
}
