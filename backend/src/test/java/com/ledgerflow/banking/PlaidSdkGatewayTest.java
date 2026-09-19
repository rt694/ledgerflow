package com.ledgerflow.banking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import retrofit2.*;

class PlaidSdkGatewayTest {
  PlaidApi api;
  PlaidSdkGateway gateway;

  @BeforeEach
  void setup() {
    api = mock(PlaidApi.class);
    gateway =
        new PlaidSdkGateway(
            new PlaidSettings(
                true,
                "client",
                "secret",
                "https://example.test/webhook",
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            api);
  }

  @Test
  void buildsSandboxRequestsAndDoesNotExposeCredentialsToTheCaller() throws Exception {
    @SuppressWarnings("unchecked")
    Call<LinkTokenCreateResponse> linkCall = mock(Call.class);
    when(api.linkTokenCreate(any())).thenReturn(linkCall);
    when(linkCall.execute())
        .thenReturn(Response.success(new LinkTokenCreateResponse().linkToken("link-sandbox-1")));
    assertThat(gateway.createLinkToken(UUID.randomUUID(), "https://example.test/webhook"))
        .isEqualTo("link-sandbox-1");
    var linkRequest = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
    verify(api).linkTokenCreate(linkRequest.capture());
    assertThat(linkRequest.getValue().getProducts()).containsExactly(Products.TRANSACTIONS);
    assertThat(linkRequest.getValue().getWebhook()).isEqualTo("https://example.test/webhook");

    @SuppressWarnings("unchecked")
    Call<ItemPublicTokenExchangeResponse> exchangeCall = mock(Call.class);
    when(api.itemPublicTokenExchange(any())).thenReturn(exchangeCall);
    when(exchangeCall.execute())
        .thenReturn(
            Response.success(
                new ItemPublicTokenExchangeResponse()
                    .accessToken("access-sandbox-secret")
                    .itemId("item-1")));
    assertThat(gateway.exchange("public-sandbox-1"))
        .isEqualTo(new PlaidGateway.Exchange("access-sandbox-secret", "item-1"));
    var exchangeRequest = ArgumentCaptor.forClass(ItemPublicTokenExchangeRequest.class);
    verify(api).itemPublicTokenExchange(exchangeRequest.capture());
    assertThat(exchangeRequest.getValue().getPublicToken()).isEqualTo("public-sandbox-1");
  }

  @Test
  void mapsDecimalTransactionsAndRecognizesRequiredPaginationRestart() throws Exception {
    var transaction =
        new com.plaid.client.model.Transaction()
            .transactionId("tx-1")
            .accountId("account-1")
            .amount(12.34)
            .isoCurrencyCode("USD")
            .date(LocalDate.of(2026, 9, 18))
            .name("Fixture")
            .pending(false);
    @SuppressWarnings("unchecked")
    Call<TransactionsSyncResponse> syncCall = mock(Call.class);
    when(api.transactionsSync(any())).thenReturn(syncCall);
    when(syncCall.execute())
        .thenReturn(
            Response.success(
                new TransactionsSyncResponse()
                    .accounts(List.of())
                    .added(List.of(transaction))
                    .modified(List.of())
                    .removed(List.of())
                    .nextCursor("next")
                    .hasMore(false)));
    var page = gateway.sync("access-sandbox-secret", "old");
    assertThat(page.added().getFirst().amount()).isEqualByComparingTo(new BigDecimal("12.34"));
    var request = ArgumentCaptor.forClass(TransactionsSyncRequest.class);
    verify(api).transactionsSync(request.capture());
    assertThat(request.getValue().getCursor()).isEqualTo("old");
    assertThat(request.getValue().getCount()).isEqualTo(500);

    reset(api, syncCall);
    when(api.transactionsSync(any())).thenReturn(syncCall);
    when(syncCall.execute())
        .thenReturn(
            Response.error(
                400,
                ResponseBody.create(
                    "{\"error_code\":\"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION\"}",
                    MediaType.get("application/json"))));
    assertThatThrownBy(() -> gateway.sync("access-sandbox-secret", "old"))
        .isInstanceOf(PlaidGateway.MutationDuringPagination.class);
  }
}
