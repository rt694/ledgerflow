package com.ledgerflow.invoicing;

import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InvoicePaymentAccess {
  private final InvoiceRepository invoices;

  public InvoicePaymentAccess(InvoiceRepository invoices) {
    this.invoices = invoices;
  }

  public Snapshot lock(UUID org, UUID id) {
    var invoice = invoices.lockByScope(id, org).orElseThrow(BusinessException::notFound);
    return new Snapshot(
        invoice.id(), invoice.total(), invoice.currency().name(), invoice.status().name());
  }

  public void settle(UUID org, UUID id, BigDecimal netPaid) {
    var invoice = invoices.lockByScope(id, org).orElseThrow(BusinessException::notFound);
    invoice.settlement(netPaid.compareTo(invoice.total()) >= 0);
    invoices.flush();
  }

  public record Snapshot(UUID id, BigDecimal total, String currency, String status) {}
}
