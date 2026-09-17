package com.ledgerflow.organization;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface MembershipRepository extends JpaRepository<Membership, UUID> {
  Optional<Membership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  Page<Membership> findByOrganizationId(UUID organizationId, Pageable pageable);

  long countByOrganizationIdAndRole(UUID organizationId, Role role);
}
