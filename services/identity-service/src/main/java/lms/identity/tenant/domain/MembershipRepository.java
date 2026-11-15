package lms.identity.tenant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository {

    Membership save(Membership membership);

    /** Các membership ACTIVE của người dùng (mọi tenant). */
    List<Membership> findActiveByUser(UUID userId);

    Optional<Membership> find(UUID userId, UUID tenantId);

    /** Người dùng có membership ở bất kỳ tenant nào (mọi trạng thái). */
    boolean existsForUser(UUID userId);

    /**
     * Ghi tenant dùng gần nhất (credentials.last_tenant_id). Tài khoản đăng nhập được luôn có dòng credentials;
     * với userId không có dòng credentials (không thể xác thực, không nhận được token) thì đây là no-op có chủ đích.
     */
    void saveLastTenant(UUID userId, UUID tenantId);

    /** Tenant dùng gần nhất; trả Optional.empty nếu chưa ghi hoặc userId không có dòng credentials. */
    Optional<UUID> findLastTenant(UUID userId);
}
