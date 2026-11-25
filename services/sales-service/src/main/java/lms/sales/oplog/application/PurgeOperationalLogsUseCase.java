package lms.sales.oplog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Xóa log quá hạn giữ (mặc định 30 ngày) theo lô cho tới khi hết hoặc chạm giới hạn số lô mỗi lượt. */
public class PurgeOperationalLogsUseCase {

    static final int MAX_BATCHES_PER_RUN = 200;

    private final OperationalLogStore store;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;

    public PurgeOperationalLogsUseCase(OperationalLogStore store, Clock clock, int retentionDays, int batchSize) {
        this.store = store;
        this.clock = clock;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, batchSize);
    }

    /** Trả tổng số dòng đã xóa. */
    public int execute() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int n = store.purgeOlderThan(cutoff, batchSize);
            total += n;
            if (n < batchSize) {
                break;
            }
        }
        return total;
    }
}
