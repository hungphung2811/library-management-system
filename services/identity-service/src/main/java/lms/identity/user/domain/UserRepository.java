package lms.identity.user.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Bảng users là toàn cục (không RLS, không @TenantId): việc thuộc tenant nào đi qua tenant_memberships. Các phương thức
 * nhận tenantId chỉ trả người có membership ở tenant đó (mọi trạng thái membership).
 */
public interface UserRepository {

    /** Lưu và flush ngay; vi phạm unique (username, email) ném DuplicateUserException. */
    User save(User user);

    /** Tra toàn cục (mọi tenant): chỉ dùng cho việc nội bộ như cấp token, không trả thẳng cho quản trị viên tenant. */
    Optional<User> findById(UUID id);

    /** Người dùng có membership ở tenantId, kèm phòng ban trong tenant đó; không có membership thì rỗng. */
    Optional<TenantUser> findInTenant(UUID id, UUID tenantId);

    /** Chỉ những người có membership (mọi trạng thái) ở tenantId trong số ids; id khác bị bỏ qua. Không có dữ liệu liên hệ cần dùng. */
    java.util.List<User> findAllInTenant(java.util.Collection<UUID> ids, UUID tenantId);

    boolean existsById(UUID id);

    /** Toàn cục (mọi tenant). */
    boolean existsByUsername(String username);

    /** Email đã được người dùng khác dùng (bỏ qua chính excludeId nếu có), không phân biệt hoa thường; toàn cục. */
    boolean existsByEmail(String email, UUID excludeId);

    /** Có membership (mọi trạng thái) ở tenantId không, kể cả khi chưa có bản ghi users. */
    boolean hasMembership(UUID userId, UUID tenantId);

    /** Số tenant mà người dùng là thành viên (mọi trạng thái membership). */
    int countMembershipsOf(UUID userId);

    /** Tìm trong criteria.tenantId (lọc qua tenant_memberships). */
    UserPage search(UserCriteria criteria);
}
