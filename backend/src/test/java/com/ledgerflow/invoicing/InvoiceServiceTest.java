package com.ledgerflow.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.ledgerflow.customer.CustomerDirectory;
import com.ledgerflow.ledger.InvoiceLedger;
import com.ledgerflow.organization.OrganizationAccess;
import com.ledgerflow.organization.Role;
import com.ledgerflow.payment.PaymentGuard;
import com.ledgerflow.shared.api.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {
  @Mock InvoiceRepository invoices;
  @Mock InvoiceLedger ledger;
  @Mock PaymentGuard payments;
  @Mock CustomerDirectory customers;
  @Mock OrganizationAccess access;
  InvoiceService service;
  UUID org = UUID.randomUUID();
  UUID actor = UUID.randomUUID();
  UUID id = UUID.randomUUID();
  Clock clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setup() {
    service = new InvoiceService(invoices, customers, access, clock, ledger, payments);
  }

  @Test
  void deniedIssueNeverLooksUpTheInvoice() {
    doThrow(BusinessException.forbidden())
        .when(access)
        .require(org, actor, Role.OWNER, Role.ACCOUNTANT);
    assertThatThrownBy(() -> service.issue(org, actor, id, 0))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(invoices, customers);
  }

  @Test
  void staleVersionStopsBeforeCustomerLookupOrMutation() {
    Invoice invoice =
        new Invoice(
            org,
            new CustomerDirectory.CustomerSnapshot(
                UUID.randomUUID(), "Customer", "test@example.test"),
            "INV-001",
            CurrencyCode.USD,
            LocalDate.of(2026, 10, 1),
            List.of(
                new InvoiceCalculator.LineInput(
                    "Item", 1, new BigDecimal("1.00"), BigDecimal.ZERO)),
            clock.instant());
    when(invoices.lockByScope(id, org)).thenReturn(Optional.of(invoice));
    assertThatThrownBy(() -> service.issue(org, actor, id, 7))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(customers);
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
  }

  @Test
  void pastDueDateFailsBeforePersistence() {
    var request =
        new InvoiceController.CreateRequest(
            UUID.randomUUID(), "INV-001", CurrencyCode.USD, LocalDate.of(2026, 9, 16), List.of());
    assertThatThrownBy(() -> service.create(org, actor, request))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(invoices, customers);
  }
}
