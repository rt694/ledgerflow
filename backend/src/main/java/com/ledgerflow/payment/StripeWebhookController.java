package com.ledgerflow.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.shared.api.BusinessException;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.time.Clock;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
  private static final Set<String> TYPES =
      Set.of(
          "payment_intent.succeeded",
          "payment_intent.payment_failed",
          "payment_intent.canceled",
          "refund.created",
          "refund.updated",
          "refund.failed",
          "charge.dispute.created",
          "charge.dispute.updated",
          "charge.dispute.closed",
          "charge.dispute.funds_withdrawn",
          "charge.dispute.funds_reinstated");
  private final StripeSettings settings;
  private final StripeGateway stripe;
  private final PaymentService payments;
  private final ObjectMapper json;
  private final Clock clock;

  StripeWebhookController(
      StripeSettings settings,
      StripeGateway stripe,
      PaymentService payments,
      ObjectMapper json,
      Clock clock) {
    this.settings = settings;
    this.stripe = stripe;
    this.payments = payments;
    this.json = json;
    this.clock = clock;
  }

  @PostMapping
  @SecurityRequirements
  public ResponseEntity<Void> receive(
      @RequestBody String payload,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
    settings.requireEnabled();
    if (payload.length() > 262144 || signature == null)
      throw BusinessException.invalid("Invalid webhook request.");
    com.fasterxml.jackson.databind.JsonNode event;
    try {
      Webhook.Signature.verifyHeader(payload, signature, settings.webhook, 300);
      for (String part : signature.split(","))
        if (part.startsWith("t=")
            && Long.parseLong(part.substring(2)) > clock.instant().getEpochSecond() + 30)
          throw new IllegalArgumentException();
      event = json.readTree(payload);
      if (!event.path("livemode").isBoolean() || event.path("livemode").asBoolean())
        throw new IllegalArgumentException();
      if (!event.path("id").isTextual()
          || event.path("id").asText().length() > 255
          || !event.path("type").isTextual()) throw new IllegalArgumentException();
    } catch (Exception exception) {
      throw BusinessException.invalid("Invalid webhook signature or event.");
    }
    String type = event.get("type").asText(), id = event.get("id").asText();
    if (!TYPES.contains(type) || payments.seen(id)) return ResponseEntity.ok().build();
    var object = event.path("data").path("object");
    if (!object.path("id").isTextual()) throw BusinessException.invalid("Missing webhook object.");
    String objectId = object.get("id").asText();
    StripeGateway.Refund refund = null;
    StripeGateway.Dispute dispute = null;
    String intentId = objectId;
    if (type.startsWith("refund.")) {
      refund = stripe.retrieveRefund(objectId);
      intentId = refund.intent();
    } else if (type.startsWith("charge.dispute.")) {
      dispute = stripe.retrieveDispute(objectId);
      intentId = dispute.intent();
    }
    if (intentId == null) throw BusinessException.invalid("Missing payment intent reference.");
    var intent = stripe.retrieve(intentId);
    if (intent.live()) throw BusinessException.invalid("Live events are not allowed.");
    payments.event(id, type, intent, refund, dispute);
    return ResponseEntity.ok().build();
  }
}
