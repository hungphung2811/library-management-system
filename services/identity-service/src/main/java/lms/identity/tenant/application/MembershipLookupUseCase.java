package lms.identity.tenant.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra cứu membership chỉ đọc cho context auth: tenant dùng gần nhất (gắn tenant cho sự kiện đăng nhập thất bại) và
 * kiểm tra một người dùng đã thuộc tenant khác hay chưa (chặn cấp credential chéo tenant). tenant_memberships và
 * credentials là bảng toàn cục nên đọc được khi chưa có tenant trong ngữ cảnh.
 */
public class MembershipLookupUseCase {

    private final MembershipRepository memberships;

    public MembershipLookupUseCase(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> lastTenant(UUID userId) {
        return memberships.findLastTenant(userId);
    }

    /** Có membership (mọi trạng thái) ở tenantId. */
    @Transactional(readOnly = true)
    public boolean isMember(UUID userId, UUID tenantId) {
        return memberships.find(userId, tenantId).isPresent();
    }

    /** Có membership (mọi trạng thái) ở bất kỳ tenant nào. */
    @Transactional(readOnly = true)
    public boolean hasAnyMembership(UUID userId) {
        return memberships.existsForUser(userId);
    }
}
