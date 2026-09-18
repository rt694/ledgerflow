package com.ledgerflow.payment;

import java.util.Map;
import java.util.UUID;

public interface StripeGateway {
  Intent create(UUID payment, UUID org, UUID invoice, long cents);

  Intent retrieve(String id);

  Intent cancel(String id, String key);

  Refund refund(String intent, long cents, UUID request);

  Refund retrieveRefund(String id);

  Dispute retrieveDispute(String id);

  record Intent(
      String id,
      long amount,
      String currency,
      String status,
      boolean live,
      String clientSecret,
      Map<String, String> metadata) {}

  record Refund(String id, String intent, long amount, String currency, String status) {}

  record Dispute(
      String id, String intent, long amount, String currency, String status, boolean live) {}
}
