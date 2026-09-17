package com.ledgerflow.customer;

import com.ledgerflow.organization.OrganizationAccess;
import com.ledgerflow.organization.Role;
import com.ledgerflow.shared.api.*;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class CustomerService {
  private final CustomerRepository customers;
  private final OrganizationAccess access;
  private final Clock clock;

  CustomerService(CustomerRepository customers, OrganizationAccess access, Clock clock) {
    this.customers = customers;
    this.access = access;
    this.clock = clock;
  }

  public CustomerController.CustomerResponse create(
      UUID org, UUID actor, CustomerController.CreateRequest request) {
    access.require(org, actor, Role.OWNER, Role.EMPLOYEE);
    return response(
        customers.save(
            new Customer(
                org, request.name().strip(), normalize(request.email()), clock.instant())));
  }

  @Transactional(readOnly = true)
  public CustomerController.CustomerResponse get(UUID org, UUID actor, UUID id) {
    access.require(org, actor);
    return response(
        customers.findByIdAndOrganizationId(id, org).orElseThrow(BusinessException::notFound));
  }

  @Transactional(readOnly = true)
  public PageResponse<CustomerController.CustomerResponse> list(
      UUID org, UUID actor, int page, int size) {
    access.require(org, actor);
    return PageResponse.from(
        customers
            .findByOrganizationId(org, PageRequest.of(page, size, Sort.by("createdAt", "id")))
            .map(this::response));
  }

  public CustomerController.CustomerResponse update(
      UUID org, UUID actor, UUID id, CustomerController.UpdateRequest request) {
    access.require(org, actor, Role.OWNER, Role.EMPLOYEE);
    Customer customer = customers.lockByScope(id, org).orElseThrow(BusinessException::notFound);
    if (customer.version() != request.version())
      throw BusinessException.conflict("Customer changed. Reload before editing.");
    customer.update(request.name().strip(), normalize(request.email()));
    return response(customer);
  }

  private String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  private CustomerController.CustomerResponse response(Customer c) {
    return new CustomerController.CustomerResponse(c.id(), c.name(), c.email(), c.version());
  }
}
