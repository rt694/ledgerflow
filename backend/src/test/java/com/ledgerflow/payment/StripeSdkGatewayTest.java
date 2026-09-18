package com.ledgerflow.payment;

import static org.assertj.core.api.Assertions.*;

import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class StripeSdkGatewayTest {
  HttpServer server;
  AtomicReference<String> body = new AtomicReference<>(),
      idempotency = new AtomicReference<>(),
      path = new AtomicReference<>();
  String response;
  int code = 200;
  StripeSdkGateway gateway;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        request -> {
          path.set(request.getRequestURI().getPath());
          body.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          idempotency.set(request.getRequestHeaders().getFirst("Idempotency-Key"));
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          request.getResponseHeaders().set("Content-Type", "application/json");
          request.sendResponseHeaders(code, bytes.length);
          try (var output = request.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    var client =
        StripeClient.builder()
            .setApiKey("sk_test_fixture")
            .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
            .setMaxNetworkRetries(0)
            .build();
    gateway =
        new StripeSdkGateway(new StripeSettings(true, "sk_test_fixture", "whsec_fixture"), client);
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  @Test
  void createSendsExactCentsCardOnlyMetadataAndStableIdempotency() {
    response =
        "{\"id\":\"pi_fixture\",\"object\":\"payment_intent\",\"amount\":6492,\"currency\":\"usd\",\"status\":\"requires_payment_method\",\"livemode\":false,\"client_secret\":\"synthetic_secret\",\"metadata\":{}}";
    var payment = UUID.randomUUID();
    var org = UUID.randomUUID();
    var invoice = UUID.randomUUID();
    var result = gateway.create(payment, org, invoice, 6492);
    assertThat(result.amount()).isEqualTo(6492);
    assertThat(result.live()).isFalse();
    assertThat(path.get()).isEqualTo("/v1/payment_intents");
    String decoded = URLDecoder.decode(body.get(), StandardCharsets.UTF_8);
    assertThat(decoded)
        .contains(
            "amount=6492",
            "currency=usd",
            "payment_method_types[0]=card",
            "metadata[ledgerflow_payment_id]=" + payment,
            "metadata[ledgerflow_organization_id]=" + org,
            "metadata[ledgerflow_invoice_id]=" + invoice);
    assertThat(idempotency.get()).isEqualTo("ledgerflow-payment-" + payment);
    gateway.create(payment, org, invoice, 6492);
    assertThat(idempotency.get()).isEqualTo("ledgerflow-payment-" + payment);
  }

  @Test
  void refundUsesPaymentIntentExactAmountAndRequestKey() {
    response =
        "{\"id\":\"re_fixture\",\"object\":\"refund\",\"amount\":6492,\"currency\":\"usd\",\"status\":\"pending\",\"payment_intent\":\"pi_fixture\"}";
    var id = UUID.randomUUID();
    var result = gateway.refund("pi_fixture", 6492, id);
    assertThat(result.intent()).isEqualTo("pi_fixture");
    assertThat(result.status()).isEqualTo("pending");
    assertThat(URLDecoder.decode(body.get(), StandardCharsets.UTF_8))
        .contains("amount=6492", "payment_intent=pi_fixture");
    assertThat(idempotency.get()).isEqualTo("ledgerflow-refund-" + id);
  }

  @Test
  void providerErrorsDoNotExposeTheirMessages() {
    code = 401;
    response =
        "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"sensitive-provider-detail\"}}";
    assertThatThrownBy(() -> gateway.retrieve("pi_fixture"))
        .isInstanceOf(com.ledgerflow.shared.api.BusinessException.class)
        .hasMessageNotContaining("sensitive-provider-detail");
  }
}
