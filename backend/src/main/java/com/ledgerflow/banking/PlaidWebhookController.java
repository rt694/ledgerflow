package com.ledgerflow.banking;

import com.fasterxml.jackson.databind.*;
import com.ledgerflow.shared.api.BusinessException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
class PlaidWebhookController {
  private static final int MAX_BODY = 256 * 1024;
  private final PlaidSettings settings;
  private final PlaidWebhookVerifier verifier;
  private final BankingService banking;
  private final ObjectMapper json;

  PlaidWebhookController(
      PlaidSettings settings,
      PlaidWebhookVerifier verifier,
      BankingService banking,
      ObjectMapper json) {
    this.settings = settings;
    this.verifier = verifier;
    this.banking = banking;
    this.json = json;
  }

  @PostMapping(path = "/api/v1/webhooks/plaid", consumes = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirements
  void webhook(
      HttpServletRequest request,
      @RequestHeader(value = "Plaid-Verification", required = false) String signature)
      throws IOException {
    settings.requireEnabled();
    if (signature == null) throw BusinessException.invalid("Plaid webhook verification failed.");
    byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) throw BusinessException.invalid("Webhook body is too large.");
    verifier.verify(signature, body);
    JsonNode event = json.readTree(body);
    if (!"sandbox".equals(event.path("environment").asText()))
      throw BusinessException.invalid("Only Plaid Sandbox webhooks are accepted.");
    String type = event.path("webhook_type").asText();
    String code = event.path("webhook_code").asText();
    String item = event.path("item_id").asText();
    if ("TRANSACTIONS".equals(type) && "SYNC_UPDATES_AVAILABLE".equals(code)) {
      if (item.isBlank()) throw BusinessException.invalid("Plaid Item is required.");
      banking.syncWebhook(item);
    } else if ("ITEM".equals(type) && "ERROR".equals(code)) {
      if (item.isBlank()) throw BusinessException.invalid("Plaid Item is required.");
      banking.itemError(item, event.path("error").path("error_code").asText("UNKNOWN"));
    }
  }
}
