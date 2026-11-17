package lms.identity.auth.application;

import java.time.Clock;
import java.time.Duration;

/**
 * Xóa refresh token đã hết hạn quá {@code retention} (mặc định 1 ngày), theo lô. Dòng thu hồi nhưng CHƯA hết hạn được
 * giữ để phát hiện dùng lại. Không bao bọc bằng transaction: mỗi lô tự có transaction ngắn ở adapter.
 */
public class PurgeExpiredRefreshTokensUseCase {

    public static final Duration DEFAULT_RETENTION = Duration.ofDays(1);

    private final SaveRefreshTokenPort port;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public PurgeExpiredRefreshTokensUseCase(SaveRefreshTokenPort port, Clock clock, Duration retention, int batchSize) {
        this.port = port;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    /** @return tổng số dòng đã xóa */
    public int execute() {
        var cutoff = clock.instant().minus(retention);
        int total = 0;
        int n;
        do {
            n = port.deleteExpiredBefore(cutoff, batchSize);
            total += n;
        } while (n >= batchSize);
        return total;
    }
}
