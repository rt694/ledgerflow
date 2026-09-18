package com.ledgerflow.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class StripeSdkGateway implements StripeGateway {
  private final StripeSettings settings;
  private final StripeClient client;

  @org.springframework.beans.factory.annotation.Autowired
  StripeSdkGateway(StripeSettings settings) {
    this(
        settings,
        StripeClient.builder()
            .setApiKey(settings.key.isBlank() ? "sk_test_disabled" : settings.key)
            .setConnectTimeout(5000)
            .setReadTimeout(15000)
            .setMaxNetworkRetries(2)
            .build());
  }

  StripeSdkGateway(StripeSettings settings, StripeClient client) {
    this.settings = settings;
    this.client = client;
  }

  private RequestOptions options(String key) {
    return RequestOptions.builder().setIdempotencyKey(key).build();
  }

  public Intent create(UUID payment, UUID org, UUID invoice, long cents) {
    settings.requireEnabled();
    try {
      return intent(
          client
              .v1()
              .paymentIntents()
              .create(
                  PaymentIntentCreateParams.builder()
                      .setAmount(cents)
                      .setCurrency("usd")
                      .addPaymentMethodType("card")
                      .putMetadata("ledgerflow_payment_id", payment.toString())
                      .putMetadata("ledgerflow_organization_id", org.toString())
                      .putMetadata("ledgerflow_invoice_id", invoice.toString())
                      .build(),
                  options("ledgerflow-payment-" + payment)));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  public Intent retrieve(String id) {
    settings.requireEnabled();
    try {
      return intent(client.v1().paymentIntents().retrieve(id));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  public Intent cancel(String id, String key) {
    settings.requireEnabled();
    try {
      return intent(
          client
              .v1()
              .paymentIntents()
              .cancel(id, PaymentIntentCancelParams.builder().build(), options(key)));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  public Refund refund(String id, long cents, UUID request) {
    settings.requireEnabled();
    try {
      return refund(
          client
              .v1()
              .refunds()
              .create(
                  RefundCreateParams.builder().setPaymentIntent(id).setAmount(cents).build(),
                  options("ledgerflow-refund-" + request)));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  public Refund retrieveRefund(String id) {
    settings.requireEnabled();
    try {
      return refund(client.v1().refunds().retrieve(id));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  public Dispute retrieveDispute(String id) {
    settings.requireEnabled();
    try {
      var dispute = client.v1().disputes().retrieve(id);
      var charge = client.v1().charges().retrieve(dispute.getCharge());
      return new Dispute(
          dispute.getId(),
          charge.getPaymentIntent(),
          dispute.getAmount(),
          dispute.getCurrency(),
          dispute.getStatus(),
          Boolean.TRUE.equals(dispute.getLivemode()) || Boolean.TRUE.equals(charge.getLivemode()));
    } catch (StripeException exception) {
      throw StripeSettings.unavailable();
    }
  }

  private Intent intent(PaymentIntent p) {
    return new Intent(
        p.getId(),
        p.getAmount(),
        p.getCurrency(),
        p.getStatus(),
        Boolean.TRUE.equals(p.getLivemode()),
        p.getClientSecret(),
        p.getMetadata());
  }

  private Refund refund(com.stripe.model.Refund r) {
    return new Refund(
        r.getId(), r.getPaymentIntent(), r.getAmount(), r.getCurrency(), r.getStatus());
  }
}
