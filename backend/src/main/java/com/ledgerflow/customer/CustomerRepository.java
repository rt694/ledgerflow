package com.ledgerflow.customer;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<Customer, UUID> {
  Optional<Customer> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Page<Customer> findByOrganizationId(UUID organizationId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Customer c where c.id = :id and c.organizationId = :organizationId")
  Optional<Customer> lockByScope(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
