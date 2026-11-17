package lms.identity.auth.application;

import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RevokeReason;
import java.time.Instant;
import java.util.UUID;

public interface SaveRefreshTokenPort {
    RefreshToken save(RefreshToken token);

    /** Thu hồi mọi refresh token còn hiệu lực của người dùng (BULK, buộc đăng nhập lại). Tự mở transaction nếu chưa có. */
    void revokeAllByUserId(UUID userId);

    /**
     * Thu hồi token cũ (nếu có) và lưu token mới trong CÙNG một giao dịch: hoặc cả hai, hoặc không gì (xoay vòng phiên,
     * đổi tenant). Adapter thật cập nhật có điều kiện {@code WHERE id = ? AND revoked = false}; 0 dòng (request song
     * song đã xoay trước) thì KHÔNG chèn token mới, rollback và ném {@code RefreshTokenSupersededException}. Bản mặc
     * định chỉ dành cho port giả trong test.
     */
    default void rotate(RefreshToken revokedOrNull, RefreshToken issued) {
        if (revokedOrNull != null) save(revokedOrNull);
        save(issued);
    }

    /** Thu hồi mọi token chưa thu hồi của một họ phiên; trả số dòng. */
    default int revokeSession(UUID sessionId, RevokeReason reason, Instant now) {
        throw new UnsupportedOperationException("revokeSession");
    }

    /** Thu hồi phiên của CHÍNH người dùng (lọc user_id + session_id trong một câu); 0 = không có/không phải của họ. */
    default int revokeSessionOfUser(UUID userId, UUID sessionId, Instant now) {
        throw new UnsupportedOperationException("revokeSessionOfUser");
    }

    /** Thu hồi mọi phiên của người dùng trừ keepSessionId (null = không giữ phiên nào); trả số dòng. */
    default int revokeAllExcept(UUID userId, UUID keepSessionId, Instant now) {
        throw new UnsupportedOperationException("revokeAllExcept");
    }

    /** Xóa tối đa batch dòng có expires_at trước cutoff; trả số dòng đã xóa. */
    default int deleteExpiredBefore(Instant cutoff, int batch) {
        throw new UnsupportedOperationException("deleteExpiredBefore");
    }
}
