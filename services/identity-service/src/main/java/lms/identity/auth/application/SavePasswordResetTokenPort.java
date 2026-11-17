package lms.identity.auth.application;

import lms.identity.auth.domain.PasswordResetToken;
import java.time.Instant;
import java.util.UUID;

public interface SavePasswordResetTokenPort {

    PasswordResetToken save(PasswordResetToken token);

    /** Vô hiệu mọi token còn hiệu lực của người dùng (đánh dấu đã dùng): chỉ token mới nhất dùng được. */
    void invalidateOutstanding(UUID userId, Instant now);

    /**
     * Đánh dấu đã dùng một cách nguyên tử (chỉ khi chưa dùng): hai yêu cầu xác nhận đồng thời với cùng token thì đúng một
     * yêu cầu nhận true.
     */
    boolean consume(UUID tokenId, Instant now);
}
