package lms.identity.user.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng sang context auth (credential: tên đăng nhập, mật khẩu, cờ platform admin). Context user không biết auth; adapter
 * nằm ở auth (auth được dùng tầng application của user). credentials là bảng toàn cục (mọi tenant).
 */
public interface AccountCredentialPort {

    /** Độ dài tối thiểu của mật khẩu (kể cả mật khẩu tạm), trùng quy tắc của context auth. */
    int MIN_PASSWORD_LENGTH = 8;

    /** userId đang giữ tên đăng nhập này (đã chuẩn hóa), rỗng nếu chưa ai dùng. */
    Optional<UUID> findUserIdByUsername(String username);

    boolean exists(UUID userId);

    /** Tên đăng nhập (đã chuẩn hóa) trong {@code usernames} đã có credential (mọi tenant); một truy vấn cho cả lô nhập. */
    default java.util.Set<String> existingUsernames(java.util.Collection<String> usernames) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (String u : usernames) if (findUserIdByUsername(u).isPresent()) found.add(u);
        return found;
    }

    /** Tài khoản có cờ platform_admin (người vận hành). Không có credential thì false. */
    boolean isPlatformAdmin(UUID userId);

    /** Tạo credential, không gắn tenant (membership do context user ghi cùng transaction). */
    void register(UUID userId, String username, String rawPassword, boolean mustChangePassword);

    /** Đặt mật khẩu tạm (buộc đổi ở lần đăng nhập sau), thu hồi mọi phiên; false nếu chưa có credential. */
    boolean resetToTemporaryPassword(UUID userId, String temporaryPassword);

    /** Mở khóa đăng nhập sai (xóa failedAttempts, lockedUntil), ghi audit; false nếu chưa có credential. */
    default boolean unlock(UUID userId, UUID actorId) {
        throw new UnsupportedOperationException("unlock");
    }

    /** Thời điểm hết khóa của những người đang bị khóa tại now; người không bị khóa không có trong map. */
    default java.util.Map<UUID, java.time.Instant> activeLocks(java.util.Collection<UUID> userIds, java.time.Instant now) {
        return java.util.Map.of();
    }

    /** Thu hồi mọi refresh token của người dùng trong tenant; trả số token đã thu hồi. */
    default int revokeSessionsInTenant(UUID userId, UUID tenantId) {
        throw new UnsupportedOperationException("revokeSessionsInTenant");
    }

    /** Như trên, kèm người thao tác để audit (ADR-0017); mặc định bỏ qua actorId. */
    default int revokeSessionsInTenant(UUID userId, UUID tenantId, UUID actorId) {
        return revokeSessionsInTenant(userId, tenantId);
    }

    /**
     * Phiên đang đăng nhập của người dùng TRONG tenant (không có phiên nền tảng hay phiên tenant khác). auditActorOrNull
     * khác null (người xem khác người được xem) thì ghi audit việc xem.
     */
    default java.util.List<AccountSession> sessionsInTenant(UUID userId, UUID tenantId, UUID auditActorOrNull) {
        throw new UnsupportedOperationException("sessionsInTenant");
    }

    /**
     * Thu hồi một phiên, lọc user + tenant + session trong cùng một câu SQL; false nếu không có phiên nào khớp (không
     * tồn tại, của người khác, ở tenant khác, phiên nền tảng, đã kết thúc). Ghi audit khi thành công.
     */
    default boolean revokeSessionInTenant(UUID userId, UUID tenantId, UUID sessionId, UUID actorId) {
        throw new UnsupportedOperationException("revokeSessionInTenant");
    }

    /** Một phiên của người dùng ở một tenant (dữ liệu từ context auth). */
    record AccountSession(UUID sessionId, String channel, String deviceSummary, String ipAddress,
                          java.time.Instant startedAt, java.time.Instant lastUsedAt, java.time.Instant expiresAt) {
    }
}
