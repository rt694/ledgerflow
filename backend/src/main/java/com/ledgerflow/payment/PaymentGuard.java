package com.ledgerflow.payment;

import com.ledgerflow.shared.api.BusinessException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentGuard {
  private final JdbcTemplate jdbc;

  public PaymentGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void requireVoidable(UUID org, UUID invoice) {
    int active =
        jdbc.queryForObject(
            "SELECT count(*) FROM ledgerflow.payments WHERE organization_id=? AND invoice_id=? AND state<>'CANCELED'",
            Integer.class,
            org,
            invoice);
    if (active > 0)
      throw BusinessException.conflict(
          "Invoices with payment history cannot be voided. Cancel an unpaid intent first.");
  }
}
