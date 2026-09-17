package com.ledgerflow.organization;

import com.ledgerflow.shared.api.BusinessException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checks current database membership, never trusting organization IDs or JWT role claims. */
@Service
@Transactional(readOnly = true)
public class OrganizationAccess {
  private final MembershipRepository memberships;

  public OrganizationAccess(MembershipRepository memberships) {
    this.memberships = memberships;
  }

  public Role require(UUID organizationId, UUID userId, Role... allowed) {
    var member =
        memberships
            .findByOrganizationIdAndUserId(organizationId, userId)
            .orElseThrow(BusinessException::notFound);
    if (allowed.length != 0 && Arrays.stream(allowed).noneMatch(role -> role == member.role())) {
      throw BusinessException.forbidden();
    }
    return member.role();
  }
}
