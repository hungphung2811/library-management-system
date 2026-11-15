package lms.identity.tenant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository {

    Membership save(Membership membership);

    /** Các membership ACTIVE của người dùng (mọi tenant). */
    List<Membership> findActiveByUser(UUID userId);

    Optional<Membership> find(UUID userId, UUID tenantId);

    /** Ghi tenant dùng gần nhất của người dùng (users.last_tenant_id). Không có dòng users thì không làm gì. */
    void saveLastTenant(UUID userId, UUID tenantId);

    Optional<UUID> findLastTenant(UUID userId);
}
