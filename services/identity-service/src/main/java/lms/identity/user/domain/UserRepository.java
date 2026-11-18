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

    /**
     * Tên đăng nhập (đã chuẩn hóa chữ thường) trong {@code usernames} đang có ở BẤT KỲ tenant nào; dùng cho nhập hàng loạt
     * (một truy vấn cho cả lô thay vì từng dòng). Mặc định hỏi từng tên; bản JPA ghi đè bằng {@code IN}.
     */
    default java.util.Set<String> existingUsernames(java.util.Collection<String> usernames) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String u : usernames) if (existsByUsername(u)) found.add(u);
        return found;
    }

    /** Như trên cho email (so khớp không phân biệt hoa thường, trả về dạng chữ thường). */
    default java.util.Set<String> existingEmails(java.util.Collection<String> emails) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String e : emails) if (existsByEmail(e, null)) found.add(e.toLowerCase(java.util.Locale.ROOT));
        return found;
    }

    /** Có membership (mọi trạng thái) ở tenantId không, kể cả khi chưa có bản ghi users. */
    boolean hasMembership(UUID userId, UUID tenantId);

    /** Số tenant mà người dùng là thành viên (mọi trạng thái membership). */
    int countMembershipsOf(UUID userId);

    /** Tìm trong criteria.tenantId (lọc qua tenant_memberships). */
    UserPage search(UserCriteria criteria);
}
