package com.ledgerflow.invoicing;

import com.ledgerflow.customer.CustomerDirectory;
import com.ledgerflow.ledger.InvoiceLedger;
import com.ledgerflow.organization.OrganizationAccess;
import com.ledgerflow.organization.Role;
import com.ledgerflow.shared.api.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class InvoiceService {
  private final InvoiceRepository invoices;
  private final CustomerDirectory customers;
  private final OrganizationAccess access;
  private final Clock clock;
  private final InvoiceLedger ledger;

  InvoiceService(
      InvoiceRepository invoices,
      CustomerDirectory customers,
      OrganizationAccess access,
      Clock clock,
      InvoiceLedger ledger) {
    this.invoices = invoices;
    this.customers = customers;
    this.access = access;
    this.clock = clock;
    this.ledger = ledger;
  }

  public InvoiceController.InvoiceResponse create(
      UUID org, UUID actor, InvoiceController.CreateRequest request) {
    access.require(org, actor);
    validDueDate(request.dueDate());
    var invoice =
        new Invoice(
            org,
            customers.require(org, request.customerId()),
            request.number(),
            request.currency(),
            request.dueDate(),
            request.lines(),
            clock.instant());
    return response(invoices.saveAndFlush(invoice));
  }

  @Transactional(readOnly = true)
  public InvoiceController.InvoiceResponse get(UUID org, UUID actor, UUID id) {
    access.require(org, actor);
    return response(
        invoices.findByIdAndOrganizationId(id, org).orElseThrow(BusinessException::notFound));
  }

  @Transactional(readOnly = true)
  public PageResponse<InvoiceController.InvoiceResponse> list(
      UUID org, UUID actor, int page, int size) {
    access.require(org, actor);
    return PageResponse.from(
        invoices
            .findByOrganizationId(org, PageRequest.of(page, size, Sort.by("createdAt", "id")))
            .map(this::response));
  }

  public InvoiceController.InvoiceResponse update(
      UUID org, UUID actor, UUID id, InvoiceController.UpdateRequest request) {
    access.require(org, actor);
    Invoice invoice = locked(org, id, request.version());
    validDueDate(request.dueDate());
    invoice.update(
        customers.require(org, request.customerId()),
        request.number(),
        request.currency(),
        request.dueDate(),
        request.lines());
    invoices.flush();
    return response(invoice);
  }

  public InvoiceController.InvoiceResponse issue(UUID org, UUID actor, UUID id, long version) {
    access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
    Invoice invoice = locked(org, id, version);
    validDueDate(invoice.dueDate());
    invoice.issue(customers.require(org, invoice.customerId()), clock.instant());
    ledger.issue(org, id, invoice.subtotal(), invoice.tax(), invoice.issuedAt());
    return response(invoice);
  }

  public InvoiceController.InvoiceResponse voidInvoice(
      UUID org, UUID actor, UUID id, long version) {
    access.require(org, actor, Role.OWNER, Role.ACCOUNTANT);
    Invoice invoice = locked(org, id, version);
    boolean issued = invoice.status() == InvoiceStatus.ISSUED;
    invoice.voidInvoice(clock.instant());
    if (issued) ledger.reverse(org, id, invoice.total(), invoice.voidedAt());
    return response(invoice);
  }

  private Invoice locked(UUID org, UUID id, long version) {
    var invoice = invoices.lockByScope(id, org).orElseThrow(BusinessException::notFound);
    if (invoice.version() != version)
      throw BusinessException.conflict("Invoice changed. Reload before continuing.");
    return invoice;
  }

  private void validDueDate(LocalDate date) {
    if (date.isBefore(LocalDate.now(clock)))
      throw BusinessException.invalid("Due date must be today or later.");
  }

  private InvoiceController.InvoiceResponse response(Invoice invoice) {
    return new InvoiceController.InvoiceResponse(
        invoice.id(),
        invoice.customerId(),
        invoice.customerName(),
        invoice.customerEmail(),
        invoice.number(),
        invoice.currency(),
        invoice.status(),
        invoice.dueDate(),
        invoice.subtotal(),
        invoice.tax(),
        invoice.total(),
        invoice.version(),
        invoice.issuedAt(),
        invoice.voidedAt(),
        invoice.lines().stream().map(InvoiceLine::response).toList());
  }
}
