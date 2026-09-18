package com.ledgerflow;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LedgerUpgradeIT extends IntegrationTestSupport {
  @Test
  void migrationBackfillsIssuedAndVoidedHistoryAndCanRestart() {
    String name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    admin.execute("CREATE DATABASE " + name);
    var url = postgres.getJdbcUrl().replace("/ledgerflow", "/" + name);
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    try {
      Flyway.configure()
          .dataSource(url, postgres.getUsername(), postgres.getPassword())
          .defaultSchema("public")
          .target("3")
          .load()
          .migrate();
      var org = UUID.randomUUID();
      var customer = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO ledgerflow.organizations(id,name,created_at) VALUES (?,'Synthetic upgrade',now())",
          org);
      jdbc.update(
          "INSERT INTO ledgerflow.customers(id,organization_id,name,email,version,created_at) VALUES (?,?,'Customer','customer@example.test',0,now())",
          customer,
          org);
      for (String status : java.util.List.of("ISSUED", "VOID", "DRAFT")) {
        jdbc.update(
            "INSERT INTO ledgerflow.invoices(id,organization_id,customer_id,customer_name,customer_email,invoice_number,currency,status,due_date,subtotal,tax,total,version,created_at,issued_at,voided_at) VALUES (?,?,?,'Customer','customer@example.test',?,'USD',?,current_date,100,8.25,108.25,0,now(),CASE WHEN ? = 'DRAFT' THEN NULL ELSE '2026-09-01T12:00:00Z'::timestamptz END,CASE WHEN ? = 'VOID' THEN '2026-09-02T12:00:00Z'::timestamptz ELSE NULL END)",
            UUID.randomUUID(),
            org,
            customer,
            status,
            status,
            status,
            status);
      }
      var flyway =
          Flyway.configure()
              .dataSource(url, postgres.getUsername(), postgres.getPassword())
              .defaultSchema("public")
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM ledgerflow.journal_transactions", Integer.class))
          .isEqualTo(3);
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM ledgerflow.journal_entries", Integer.class))
          .isEqualTo(9);
      assertThat(
              jdbc.queryForObject(
                  "SELECT sum(debit-credit) FROM ledgerflow.journal_entries WHERE account_code='RECEIVABLES'",
                  BigDecimal.class))
          .isEqualByComparingTo("108.25");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM ledgerflow.journal_transactions WHERE operation='ISSUE' AND posted_at='2026-09-01T12:00:00Z'",
                  Integer.class))
          .isEqualTo(2);
      assertThat(flyway.migrate().migrationsExecuted).isZero();
    } finally {
      admin.execute("DROP DATABASE " + name);
    }
  }
}
