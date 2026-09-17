package com.ledgerflow.organization;

import com.ledgerflow.identity.UserDirectory;
import com.ledgerflow.shared.api.BusinessException;
import com.ledgerflow.shared.api.PageResponse;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class OrganizationService {
  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;
  private final OrganizationAccess access;
  private final UserDirectory users;
  private final Clock clock;

  OrganizationService(
      OrganizationRepository organizations,
      MembershipRepository memberships,
      OrganizationAccess access,
      UserDirectory users,
      Clock clock) {
    this.organizations = organizations;
    this.memberships = memberships;
    this.access = access;
    this.users = users;
    this.clock = clock;
  }

  public OrganizationController.OrganizationResponse create(UUID actor, String name) {
    users.require(actor);
    Organization org = organizations.save(new Organization(name.strip(), clock.instant()));
    memberships.save(new Membership(org.id(), actor, Role.OWNER, clock.instant()));
    return new OrganizationController.OrganizationResponse(org.id(), org.name(), Role.OWNER);
  }

  @Transactional(readOnly = true)
  public PageResponse<OrganizationController.OrganizationResponse> list(
      UUID actor, int page, int size) {
    return PageResponse.from(
        organizations
            .findForUser(actor, paging(page, size))
            .map(
                org ->
                    new OrganizationController.OrganizationResponse(
                        org.id(), org.name(), access.require(org.id(), actor))));
  }

  @Transactional(readOnly = true)
  public OrganizationController.OrganizationResponse get(UUID id, UUID actor) {
    Role role = access.require(id, actor);
    Organization org = organizations.findById(id).orElseThrow(BusinessException::notFound);
    return new OrganizationController.OrganizationResponse(org.id(), org.name(), role);
  }

  @Transactional(readOnly = true)
  public PageResponse<OrganizationController.MemberResponse> members(
      UUID id, UUID actor, int page, int size) {
    access.require(id, actor, Role.OWNER);
    return PageResponse.from(
        memberships.findByOrganizationId(id, paging(page, size)).map(this::memberResponse));
  }

  public OrganizationController.MemberResponse addMember(
      UUID id, UUID actor, String email, Role role) {
    lockForOwner(id, actor);
    var user = users.requireByEmail(email);
    if (memberships.findByOrganizationIdAndUserId(id, user.id()).isPresent()) {
      throw BusinessException.conflict("This user is already a member.");
    }
    return memberResponse(memberships.save(new Membership(id, user.id(), role, clock.instant())));
  }

  public OrganizationController.MemberResponse changeRole(
      UUID id, UUID actor, UUID target, Role role) {
    lockForOwner(id, actor);
    var member =
        memberships
            .findByOrganizationIdAndUserId(id, target)
            .orElseThrow(BusinessException::notFound);
    protectLastOwner(id, member, role);
    member.changeRole(role);
    return memberResponse(member);
  }

  public void removeMember(UUID id, UUID actor, UUID target) {
    lockForOwner(id, actor);
    var member =
        memberships
            .findByOrganizationIdAndUserId(id, target)
            .orElseThrow(BusinessException::notFound);
    protectLastOwner(id, member, null);
    memberships.delete(member);
  }

  private void protectLastOwner(UUID id, Membership member, Role nextRole) {
    if (member.role() == Role.OWNER
        && nextRole != Role.OWNER
        && memberships.countByOrganizationIdAndRole(id, Role.OWNER) == 1) {
      throw BusinessException.conflict("An organization must keep at least one owner.");
    }
  }

  private void lockForOwner(UUID id, UUID actor) {
    organizations.lockById(id).orElseThrow(BusinessException::notFound);
    access.require(id, actor, Role.OWNER);
  }

  private OrganizationController.MemberResponse memberResponse(Membership member) {
    var user = users.require(member.userId());
    return new OrganizationController.MemberResponse(
        user.id(), user.email(), user.displayName(), member.role());
  }

  private Pageable paging(int page, int size) {
    return PageRequest.of(page, size, Sort.by("createdAt", "id"));
  }
}
