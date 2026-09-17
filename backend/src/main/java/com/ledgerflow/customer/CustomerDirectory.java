package com.ledgerflow.customer;

import com.ledgerflow.shared.api.BusinessException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public customer lookup for already-authorized organization workflows. */
@Service
@Transactional(readOnly = true)
public class CustomerDirectory {
  private final CustomerRepository customers;

  public CustomerDirectory(CustomerRepository customers) {
    this.customers = customers;
  }

  public CustomerSnapshot require(UUID organizationId, UUID customerId) {
    var customer =
        customers
            .findByIdAndOrganizationId(customerId, organizationId)
            .orElseThrow(BusinessException::notFound);
    return new CustomerSnapshot(customer.id(), customer.name(), customer.email());
  }

  public record CustomerSnapshot(UUID id, String name, String email) {}
}
