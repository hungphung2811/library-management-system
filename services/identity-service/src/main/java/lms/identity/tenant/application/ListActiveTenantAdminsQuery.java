package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.MembershipStatus;

/**
 * Quản trị viên (role {@code is_admin}) còn hoạt động của một tenant: người nhận nhắc hạn hợp đồng (ADR-0012 mục 2.4.3).
 * Phải chạy trong {@code TenantContext.callAs(tenantId)} và trong transaction (đọc role chịu RLS); "còn hoạt động" là
 * membership ACTIVE trong tenant đó.
 */
public class ListActiveTenantAdminsQuery {

    private final TenantRoleProvisioningPort roles;
    private final MembershipRepository memberships;

    public ListActiveTenantAdminsQuery(TenantRoleProvisioningPort roles, MembershipRepository memberships) {
        this.roles = roles;
        this.memberships = memberships;
    }

    public List<UUID> execute(UUID tenantId) {
        return roles.listTenantAdmins().stream()
                .filter(userId -> memberships.find(userId, tenantId)
                        .map(Membership::status).filter(s -> s == MembershipStatus.ACTIVE).isPresent())
                .toList();
    }
}
