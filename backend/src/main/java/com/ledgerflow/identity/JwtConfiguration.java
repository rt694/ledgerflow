package com.ledgerflow.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  RSAPublicKey jwtPublicKey(@Value("${ledgerflow.security.jwt.public-key}") Resource resource)
      throws java.io.IOException {
    try (var stream = resource.getInputStream()) {
      RSAPublicKey key = RsaKeyConverters.x509().convert(stream);
      if (key == null || key.getModulus().bitLength() < 2048)
        throw new IllegalStateException("JWT public key must be RSA 2048-bit or stronger.");
      return key;
    }
  }

  @Bean
  JwtEncoder jwtEncoder(
      RSAPublicKey publicKey, @Value("${ledgerflow.security.jwt.private-key}") Resource resource)
      throws java.io.IOException {
    try (var stream = resource.getInputStream()) {
      RSAPrivateKey key = RsaKeyConverters.pkcs8().convert(stream);
      if (key == null || !key.getModulus().equals(publicKey.getModulus()))
        throw new IllegalStateException("JWT keys must be a matching RSA pair.");
      RSAKey rsa = new RSAKey.Builder(publicKey).privateKey(key).build();
      return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
    }
  }

  @Bean
  JwtDecoder jwtDecoder(
      RSAPublicKey publicKey,
      @Value("${ledgerflow.security.jwt.issuer}") String issuer,
      Clock clock) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    OAuth2TokenValidator<Jwt> requiredClaims =
        jwt -> {
          boolean validSubject;
          try {
            validSubject = UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject());
          } catch (RuntimeException exception) {
            validSubject = false;
          }
          if (validSubject
              && jwt.getAudience() != null
              && jwt.getAudience().contains("ledgerflow-api")
              && jwt.getExpiresAt() != null
              && jwt.getIssuedAt() != null
              && !jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
              && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
              && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())
                      .compareTo(Duration.ofMinutes(15))
                  <= 0) {
            return OAuth2TokenValidatorResult.success();
          }
          return OAuth2TokenValidatorResult.failure(
              new OAuth2Error("invalid_token", "Required token claims are invalid.", null));
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ofSeconds(30)),
            new JwtIssuerValidator(issuer),
            requiredClaims));
    return decoder;
  }
}
