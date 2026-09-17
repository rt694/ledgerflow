package com.ledgerflow.organization;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface OrganizationRepository extends JpaRepository<Organization, UUID> {
  @Query(
      "select o from Organization o where exists (select m.id from Membership m where m.organizationId = o.id and m.userId = :userId)")
  Page<Organization> findForUser(@Param("userId") UUID userId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Organization o where o.id = :id")
  Optional<Organization> lockById(@Param("id") UUID id);
}
