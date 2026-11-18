package lms.cache;

import java.time.Duration;

/**
 * Giới hạn tốc độ theo cửa sổ cố định (fixed window) trên Redis, khóa theo tenant của request nên một tenant dồn lưu
 * lượng không ăn hạn mức của tenant khác.
 *
 * <pre>{@code
 * if (!limiter.tryAcquire("order.create.user", userId.toString(), 5, Duration.ofMinutes(1))) {
 *     throw rateLimited();
 * }
 * }</pre>
 *
 * <p>{@code bucket} là tên loại giới hạn (đăng ký, đăng nhập, OTP, đặt đơn, thanh toán lại...), {@code subject} là đối
 * tượng bị đếm (user, IP, thiết bị, mặt hàng, hoặc hằng như "tenant"). Fail-open: Redis lỗi hoặc tắt thì cho qua (có log),
 * nên đây là lớp bổ sung chứ không thay giới hạn IP ở nginx.
 */
public interface RateLimiter {

    /** Tính một lượt cho {@code subject} trong {@code bucket}; true nếu chưa vượt {@code limit} lượt/{@code window}. */
    boolean tryAcquire(String bucket, String subject, int limit, Duration window);

    /** false khi tắt (luôn cho qua). */
    boolean isEnabled();
}
