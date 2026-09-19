package com.ledgerflow.banking;

import com.google.gson.JsonParser;
import com.plaid.client.ApiClient;
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import retrofit2.Call;

@Component
class PlaidSdkGateway implements PlaidGateway {
  private final PlaidSettings settings;
  private final PlaidApi api;

  @org.springframework.beans.factory.annotation.Autowired
  PlaidSdkGateway(PlaidSettings settings) {
    this(settings, client(settings));
  }

  PlaidSdkGateway(PlaidSettings settings, PlaidApi api) {
    this.settings = settings;
    this.api = api;
  }

  private static PlaidApi client(PlaidSettings settings) {
    var client = new ApiClient(Map.of("clientId", settings.clientId, "secret", settings.secret));
    client.setPlaidAdapter(ApiClient.Sandbox);
    client.setTimeout(15);
    return client.createService(PlaidApi.class);
  }

  @Override
  public String createLinkToken(UUID userId, String webhookUrl) {
    settings.requireEnabled();
    var request =
        new LinkTokenCreateRequest()
            .clientName("LedgerFlow")
            .language("en")
            .countryCodes(List.of(CountryCode.US))
            .products(List.of(Products.TRANSACTIONS))
            .user(new LinkTokenCreateRequestUser().clientUserId(userId.toString()))
            .webhook(webhookUrl);
    return execute(api.linkTokenCreate(request), LinkTokenCreateResponse::getLinkToken);
  }

  @Override
  public Exchange exchange(String publicToken) {
    settings.requireEnabled();
    return execute(
        api.itemPublicTokenExchange(new ItemPublicTokenExchangeRequest().publicToken(publicToken)),
        response -> new Exchange(response.getAccessToken(), response.getItemId()));
  }

  @Override
  public SyncPage sync(String accessToken, String cursor) {
    settings.requireEnabled();
    var request = new TransactionsSyncRequest().accessToken(accessToken).count(500);
    if (cursor != null && !cursor.isBlank()) request.cursor(cursor);
    return execute(
        api.transactionsSync(request),
        response ->
            new SyncPage(
                safe(response.getAccounts()).stream().map(this::account).toList(),
                safe(response.getAdded()).stream().map(this::transaction).toList(),
                safe(response.getModified()).stream().map(this::transaction).toList(),
                safe(response.getRemoved()).stream()
                    .map(RemovedTransaction::getTransactionId)
                    .toList(),
                response.getNextCursor(),
                Boolean.TRUE.equals(response.getHasMore())));
  }

  @Override
  public VerificationKey verificationKey(String id) {
    settings.requireEnabled();
    return execute(
        api.webhookVerificationKeyGet(new WebhookVerificationKeyGetRequest().keyId(id)),
        response -> {
          var key = response.getKey();
          return new VerificationKey(
              key.getAlg(),
              key.getCrv(),
              key.getKty(),
              key.getUse(),
              key.getKid(),
              key.getX(),
              key.getY(),
              key.getCreatedAt(),
              key.getExpiredAt() == null ? null : key.getExpiredAt().longValue());
        });
  }

  private Account account(AccountBase source) {
    var balance = source.getBalances();
    return new Account(
        source.getAccountId(),
        source.getName(),
        source.getOfficialName(),
        source.getMask(),
        String.valueOf(source.getType()),
        source.getSubtype() == null ? null : source.getSubtype().toString(),
        balance.getIsoCurrencyCode(),
        decimal(balance.getCurrent()),
        decimal(balance.getAvailable()));
  }

  private Transaction transaction(com.plaid.client.model.Transaction source) {
    return new Transaction(
        source.getTransactionId(),
        source.getAccountId(),
        decimal(source.getAmount()),
        source.getIsoCurrencyCode(),
        source.getDate(),
        source.getAuthorizedDate(),
        source.getName(),
        source.getMerchantName(),
        Boolean.TRUE.equals(source.getPending()),
        source.getPendingTransactionId());
  }

  private BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private <T, R> R execute(Call<T> call, Function<T, R> mapper) {
    try {
      var response = call.execute();
      if (response.isSuccessful() && response.body() != null) return mapper.apply(response.body());
      String body = response.errorBody() == null ? "" : response.errorBody().string();
      if (mutation(body)) throw new MutationDuringPagination();
      throw PlaidSettings.unavailable();
    } catch (MutationDuringPagination exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof com.ledgerflow.shared.api.BusinessException business) throw business;
      throw PlaidSettings.unavailable();
    }
  }

  private boolean mutation(String body) {
    try {
      return "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"
          .equals(JsonParser.parseString(body).getAsJsonObject().get("error_code").getAsString());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private <T> List<T> safe(List<T> value) {
    return value == null ? List.of() : value;
  }
}
