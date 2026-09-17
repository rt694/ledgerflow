package com.ledgerflow.invoicing;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
  Optional<Invoice> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Page<Invoice> findByOrganizationId(UUID organizationId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Invoice i where i.id = :id and i.organizationId = :organizationId")
  Optional<Invoice> lockByScope(@Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
