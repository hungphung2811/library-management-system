package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm người dùng vào tenant (membership ACTIVE). Idempotent: đã có membership (kể cả DISABLED) thì giữ nguyên, không
 * tự bật lại membership đã bị tắt.
 */
public class AddMembershipUseCase {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;

    public AddMembershipUseCase(TenantRepository tenants, MembershipRepository memberships) {
        this.tenants = tenants;
        this.memberships = memberships;
    }

    /** @throws TenantNotFoundException nếu tenant không tồn tại */
    @Transactional
    public void execute(UUID userId, UUID tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw new TenantNotFoundException("Không tìm thấy tenant " + tenantId);
        }
        if (memberships.find(userId, tenantId).isPresent()) return;
        memberships.save(Membership.create(userId, tenantId, null));
    }
}
