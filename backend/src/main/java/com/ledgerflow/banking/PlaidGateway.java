package com.ledgerflow.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PlaidGateway {
  record Exchange(String accessToken, String itemId) {}

  record Account(
      String providerId,
      String name,
      String officialName,
      String mask,
      String type,
      String subtype,
      String currency,
      BigDecimal current,
      BigDecimal available) {}

  record Transaction(
      String providerId,
      String accountId,
      BigDecimal amount,
      String currency,
      LocalDate date,
      LocalDate authorizedDate,
      String name,
      String merchant,
      boolean pending,
      String pendingTransactionId) {}

  record SyncPage(
      List<Account> accounts,
      List<Transaction> added,
      List<Transaction> modified,
      List<String> removed,
      String nextCursor,
      boolean hasMore) {}

  record VerificationKey(
      String algorithm,
      String curve,
      String keyType,
      String use,
      String id,
      String x,
      String y,
      long createdAt,
      Long expiredAt) {}

  String createLinkToken(UUID userId, String webhookUrl);

  Exchange exchange(String publicToken);

  SyncPage sync(String accessToken, String cursor);

  VerificationKey verificationKey(String id);

  final class MutationDuringPagination extends RuntimeException {}
}
