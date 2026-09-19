package com.ledgerflow.banking;

import com.ledgerflow.organization.OrganizationAccess;
import com.ledgerflow.organization.Role;
import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BankingService {
  private static final int MAX_PAGES = 50;
  private final JdbcTemplate jdbc;
  private final OrganizationAccess access;
  private final PlaidGateway plaid;
  private final PlaidSettings settings;
  private final TokenCipher cipher;
  private final Clock clock;
  private final TransactionTemplate tx;

  BankingService(
      JdbcTemplate jdbc,
      OrganizationAccess access,
      PlaidGateway plaid,
      PlaidSettings settings,
      TokenCipher cipher,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.access = access;
    this.plaid = plaid;
    this.settings = settings;
    this.cipher = cipher;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
  }

  record Connection(
      UUID id,
      String itemId,
      String state,
      String errorCode,
      Instant createdAt,
      Instant lastSyncedAt) {}

  record Account(
      UUID id,
      UUID connectionId,
      String name,
      String officialName,
      String mask,
      String type,
      String subtype,
      String currency,
      BigDecimal currentBalance,
      BigDecimal availableBalance,
      boolean active) {}

  record BankTransaction(
      UUID id,
      UUID accountId,
      String providerTransactionId,
      BigDecimal amount,
      String currency,
      LocalDate date,
      LocalDate authorizedDate,
      String name,
      String merchantName,
      boolean pending,
      boolean removed) {}

  private record SecretConnection(
      UUID id, UUID organizationId, byte[] encrypted, byte[] iv, String cursor, String state) {}

  private record Reservation(Connection connection, boolean exchange) {}

  String linkToken(UUID organizationId, UUID actor) {
    settings.requireEnabled();
    tx.executeWithoutResult(
        s -> access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT));
    return plaid.createLinkToken(actor, settings.webhookUrl);
  }

  Connection exchange(UUID organizationId, UUID actor, String publicToken, String idempotencyKey) {
    settings.requireEnabled();
    if (!publicToken.startsWith("public-sandbox-"))
      throw BusinessException.invalid("Only Plaid Sandbox public tokens are accepted.");
    var reservation =
        tx.execute(
            status -> {
              access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
              var matches =
                  jdbc.query(
                      "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? AND idempotency_hash=? FOR UPDATE",
                      this::connection,
                      organizationId,
                      hash(idempotencyKey));
              if (!matches.isEmpty()) {
                var existing = matches.getFirst();
                String submitted =
                    jdbc.queryForObject(
                        "SELECT public_token_hash FROM ledgerflow.bank_connections WHERE id=?",
                        String.class,
                        existing.id());
                if (!MessageDigest.isEqual(
                    submitted.getBytes(StandardCharsets.US_ASCII),
                    hash(publicToken).getBytes(StandardCharsets.US_ASCII)))
                  throw BusinessException.conflict(
                      "Idempotency key belongs to another bank connection.");
                if (existing.state().equals("EXCHANGING"))
                  throw BusinessException.conflict(
                      "This connection exchange is still being resolved. Create a new Link token if it does not finish.");
                return new Reservation(existing, false);
              }
              UUID id = UUID.randomUUID();
              Instant now = clock.instant();
              jdbc.update(
                  "INSERT INTO ledgerflow.bank_connections(id,organization_id,idempotency_hash,public_token_hash,state,created_at,updated_at) VALUES (?,?,?,?,'EXCHANGING',?,?)",
                  id,
                  organizationId,
                  hash(idempotencyKey),
                  hash(publicToken),
                  Timestamp.from(now),
                  Timestamp.from(now));
              return new Reservation(requireConnection(organizationId, id), true);
            });
    if (!reservation.exchange()) return reservation.connection();
    PlaidGateway.Exchange result = plaid.exchange(publicToken);
    if (!result.accessToken().startsWith("access-sandbox-") || result.itemId().isBlank())
      throw PlaidSettings.unavailable();
    return tx.execute(
        status -> {
          access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
          Connection current = lockConnection(organizationId, reservation.connection().id());
          if (!current.state().equals("EXCHANGING")) return current;
          TokenCipher.Sealed sealed =
              cipher.encrypt(result.accessToken(), organizationId, current.id());
          try {
            jdbc.update(
                "UPDATE ledgerflow.bank_connections SET provider_item_id=?,access_token_cipher=?,access_token_iv=?,state='ACTIVE',updated_at=? WHERE id=?",
                result.itemId(),
                sealed.cipher(),
                sealed.iv(),
                Timestamp.from(clock.instant()),
                current.id());
          } catch (RuntimeException duplicateItem) {
            throw BusinessException.conflict("This Plaid Item is already connected.");
          }
          return requireConnection(organizationId, current.id());
        });
  }

  List<Connection> connections(UUID organizationId, UUID actor) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          return jdbc.query(
              "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? ORDER BY created_at,id",
              this::connection,
              organizationId);
        });
  }

  Connection get(UUID organizationId, UUID actor, UUID id) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          return requireConnection(organizationId, id);
        });
  }

  Connection sync(UUID organizationId, UUID actor, UUID id) {
    SecretConnection secret =
        tx.execute(
            status -> {
              access.require(organizationId, actor, Role.OWNER, Role.ACCOUNTANT);
              return requireSecret(organizationId, id);
            });
    return synchronize(secret);
  }

  void syncWebhook(String itemId) {
    var found =
        jdbc.query(
            "SELECT * FROM ledgerflow.bank_connections WHERE provider_item_id=?",
            this::secret,
            itemId);
    if (!found.isEmpty()) synchronize(found.getFirst());
  }

  void itemError(String itemId, String code) {
    String state = "ITEM_LOGIN_REQUIRED".equals(code) ? "LOGIN_REQUIRED" : "ERROR";
    jdbc.update(
        "UPDATE ledgerflow.bank_connections SET state=?,error_code=?,updated_at=? WHERE provider_item_id=?",
        state,
        safe(code, 100),
        Timestamp.from(clock.instant()),
        itemId);
  }

  List<Account> accounts(UUID organizationId, UUID actor, UUID connectionId) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          requireConnection(organizationId, connectionId);
          return jdbc.query(
              "SELECT * FROM ledgerflow.bank_accounts WHERE organization_id=? AND connection_id=? ORDER BY name,id",
              this::account,
              organizationId,
              connectionId);
        });
  }

  List<BankTransaction> transactions(UUID organizationId, UUID actor, boolean includeRemoved) {
    return tx.execute(
        status -> {
          access.require(organizationId, actor);
          return jdbc.query(
              "SELECT * FROM ledgerflow.bank_transactions WHERE organization_id=? AND (? OR removed_at IS NULL) ORDER BY transaction_date DESC,id LIMIT 100",
              this::bankTransaction,
              organizationId,
              includeRemoved);
        });
  }

  private Connection synchronize(SecretConnection secret) {
    if (!secret.state().equals("ACTIVE"))
      throw BusinessException.conflict("Reconnect this bank before syncing it.");
    String accessToken =
        cipher.decrypt(secret.encrypted(), secret.iv(), secret.organizationId(), secret.id());
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        var accounts = new LinkedHashMap<String, PlaidGateway.Account>();
        var added = new ArrayList<PlaidGateway.Transaction>();
        var modified = new ArrayList<PlaidGateway.Transaction>();
        var removed = new ArrayList<String>();
        String cursor = secret.cursor();
        int pages = 0;
        do {
          if (++pages > MAX_PAGES) throw PlaidSettings.unavailable();
          PlaidGateway.SyncPage page = plaid.sync(accessToken, cursor);
          page.accounts().forEach(account -> accounts.put(account.providerId(), account));
          added.addAll(page.added());
          modified.addAll(page.modified());
          removed.addAll(page.removed());
          cursor = page.nextCursor();
          if (!page.hasMore()) break;
        } while (true);
        String nextCursor = cursor;
        return tx.execute(
            status -> apply(secret, nextCursor, accounts.values(), added, modified, removed));
      } catch (PlaidGateway.MutationDuringPagination mutation) {
        // Plaid requires the entire loop to restart from the original cursor.
      }
    }
    throw PlaidSettings.unavailable();
  }

  private Connection apply(
      SecretConnection expected,
      String nextCursor,
      Collection<PlaidGateway.Account> accounts,
      List<PlaidGateway.Transaction> added,
      List<PlaidGateway.Transaction> modified,
      List<String> removed) {
    var current = lockSecret(expected.organizationId(), expected.id());
    if (!Objects.equals(current.cursor(), expected.cursor()))
      throw BusinessException.conflict("Another bank sync finished first. Try again.");
    jdbc.update(
        "UPDATE ledgerflow.bank_accounts SET active=false WHERE organization_id=? AND connection_id=?",
        expected.organizationId(),
        expected.id());
    for (var account : accounts) upsertAccount(expected, account);
    for (var value : added) upsertTransaction(expected, value, "ADDED");
    for (var value : modified) upsertTransaction(expected, value, "MODIFIED");
    for (var providerId : removed) removeTransaction(expected, providerId);
    Instant now = clock.instant();
    jdbc.update(
        "UPDATE ledgerflow.bank_connections SET sync_cursor=?,state='ACTIVE',error_code=NULL,last_synced_at=?,updated_at=? WHERE id=?",
        nextCursor,
        Timestamp.from(now),
        Timestamp.from(now),
        expected.id());
    return requireConnection(expected.organizationId(), expected.id());
  }

  private void upsertAccount(SecretConnection connection, PlaidGateway.Account value) {
    currency(value.currency());
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO ledgerflow.bank_accounts(id,organization_id,connection_id,provider_account_id,name,official_name,mask,account_type,account_subtype,currency,current_balance,available_balance,active,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,true,?) ON CONFLICT (organization_id,provider_account_id) DO UPDATE SET name=EXCLUDED.name,official_name=EXCLUDED.official_name,mask=EXCLUDED.mask,account_type=EXCLUDED.account_type,account_subtype=EXCLUDED.account_subtype,currency=EXCLUDED.currency,current_balance=EXCLUDED.current_balance,available_balance=EXCLUDED.available_balance,active=true,updated_at=EXCLUDED.updated_at",
        UUID.randomUUID(),
        connection.organizationId(),
        connection.id(),
        value.providerId(),
        safe(value.name(), 200),
        safe(value.officialName(), 200),
        safe(value.mask(), 12),
        safe(value.type(), 40),
        safe(value.subtype(), 80),
        value.currency(),
        value.current(),
        value.available(),
        Timestamp.from(now));
  }

  private void upsertTransaction(
      SecretConnection connection, PlaidGateway.Transaction value, String change) {
    currency(value.currency());
    UUID account =
        jdbc
            .query(
                "SELECT id FROM ledgerflow.bank_accounts WHERE organization_id=? AND provider_account_id=?",
                (rs, row) -> rs.getObject(1, UUID.class),
                connection.organizationId(),
                value.accountId())
            .stream()
            .findFirst()
            .orElseThrow(
                () -> BusinessException.invalid("Plaid returned an unknown bank account."));
    UUID id =
        jdbc
            .query(
                "SELECT id FROM ledgerflow.bank_transactions WHERE organization_id=? AND provider_transaction_id=?",
                (rs, row) -> rs.getObject(1, UUID.class),
                connection.organizationId(),
                value.providerId())
            .stream()
            .findFirst()
            .orElse(UUID.randomUUID());
    Instant now = clock.instant();
    jdbc.update(
        "INSERT INTO ledgerflow.bank_transactions(id,organization_id,connection_id,bank_account_id,provider_transaction_id,amount,currency,transaction_date,authorized_date,name,merchant_name,pending,pending_provider_transaction_id,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (organization_id,provider_transaction_id) DO UPDATE SET bank_account_id=EXCLUDED.bank_account_id,amount=EXCLUDED.amount,currency=EXCLUDED.currency,transaction_date=EXCLUDED.transaction_date,authorized_date=EXCLUDED.authorized_date,name=EXCLUDED.name,merchant_name=EXCLUDED.merchant_name,pending=EXCLUDED.pending,pending_provider_transaction_id=EXCLUDED.pending_provider_transaction_id,removed_at=NULL,updated_at=EXCLUDED.updated_at",
        id,
        connection.organizationId(),
        connection.id(),
        account,
        value.providerId(),
        value.amount(),
        value.currency(),
        value.date(),
        value.authorizedDate(),
        safe(value.name(), 500),
        safe(value.merchant(), 300),
        value.pending(),
        safe(value.pendingTransactionId(), 128),
        Timestamp.from(now));
    jdbc.update(
        "INSERT INTO ledgerflow.bank_transaction_changes(id,organization_id,connection_id,transaction_id,provider_transaction_id,change_type,amount,currency,transaction_date,name,pending,observed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        connection.organizationId(),
        connection.id(),
        id,
        value.providerId(),
        change,
        value.amount(),
        value.currency(),
        value.date(),
        safe(value.name(), 500),
        value.pending(),
        Timestamp.from(now));
  }

  private void removeTransaction(SecretConnection connection, String providerId) {
    var rows =
        jdbc.query(
            "SELECT * FROM ledgerflow.bank_transactions WHERE organization_id=? AND provider_transaction_id=?",
            this::bankTransaction,
            connection.organizationId(),
            providerId);
    Instant now = clock.instant();
    UUID transactionId = rows.isEmpty() ? null : rows.getFirst().id();
    if (transactionId != null)
      jdbc.update(
          "UPDATE ledgerflow.bank_transactions SET removed_at=?,updated_at=? WHERE id=?",
          Timestamp.from(now),
          Timestamp.from(now),
          transactionId);
    BankTransaction value = rows.isEmpty() ? null : rows.getFirst();
    jdbc.update(
        "INSERT INTO ledgerflow.bank_transaction_changes(id,organization_id,connection_id,transaction_id,provider_transaction_id,change_type,amount,currency,transaction_date,name,pending,observed_at) VALUES (?,?,?,?,?,'REMOVED',?,?,?,?,?,?)",
        UUID.randomUUID(),
        connection.organizationId(),
        connection.id(),
        transactionId,
        providerId,
        value == null ? null : value.amount(),
        value == null ? null : value.currency(),
        value == null ? null : value.date(),
        value == null ? null : value.name(),
        value == null ? null : value.pending(),
        Timestamp.from(now));
  }

  private Connection requireConnection(UUID organizationId, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? AND id=?",
            this::connection,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private Connection lockConnection(UUID organizationId, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? AND id=? FOR UPDATE",
            this::connection,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private SecretConnection requireSecret(UUID organizationId, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? AND id=?",
            this::secret,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private SecretConnection lockSecret(UUID organizationId, UUID id) {
    return jdbc
        .query(
            "SELECT * FROM ledgerflow.bank_connections WHERE organization_id=? AND id=? FOR UPDATE",
            this::secret,
            organizationId,
            id)
        .stream()
        .findFirst()
        .orElseThrow(BusinessException::notFound);
  }

  private Connection connection(ResultSet rs, int row) throws SQLException {
    return new Connection(
        rs.getObject("id", UUID.class),
        rs.getString("provider_item_id"),
        rs.getString("state"),
        rs.getString("error_code"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "last_synced_at"));
  }

  private SecretConnection secret(ResultSet rs, int row) throws SQLException {
    return new SecretConnection(
        rs.getObject("id", UUID.class),
        rs.getObject("organization_id", UUID.class),
        rs.getBytes("access_token_cipher"),
        rs.getBytes("access_token_iv"),
        rs.getString("sync_cursor"),
        rs.getString("state"));
  }

  private Account account(ResultSet rs, int row) throws SQLException {
    return new Account(
        rs.getObject("id", UUID.class),
        rs.getObject("connection_id", UUID.class),
        rs.getString("name"),
        rs.getString("official_name"),
        rs.getString("mask"),
        rs.getString("account_type"),
        rs.getString("account_subtype"),
        rs.getString("currency"),
        rs.getBigDecimal("current_balance"),
        rs.getBigDecimal("available_balance"),
        rs.getBoolean("active"));
  }

  private BankTransaction bankTransaction(ResultSet rs, int row) throws SQLException {
    return new BankTransaction(
        rs.getObject("id", UUID.class),
        rs.getObject("bank_account_id", UUID.class),
        rs.getString("provider_transaction_id"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getObject("transaction_date", LocalDate.class),
        rs.getObject("authorized_date", LocalDate.class),
        rs.getString("name"),
        rs.getString("merchant_name"),
        rs.getBoolean("pending"),
        rs.getTimestamp("removed_at") != null);
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private void currency(String value) {
    if (value == null || !value.matches("[A-Z]{3}"))
      throw BusinessException.invalid("Only ISO currency transactions can be imported.");
  }

  private String safe(String value, int length) {
    if (value == null) return null;
    return value.substring(0, Math.min(value.length(), length));
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
