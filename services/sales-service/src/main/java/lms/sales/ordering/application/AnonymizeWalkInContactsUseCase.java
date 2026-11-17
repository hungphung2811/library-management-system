package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.RetentionPolicy;
import lms.sales.settings.application.GetSalesSettingsUseCase;

/**
 * Ẩn danh tên và điện thoại khách vãng lai của MỘT tenant theo {@code walkInPiiRetentionMonths} (mặc định 12, 1..120),
 * cập nhật theo lô (mặc định 500 đơn mỗi transaction). Idempotent: chạy lại hoặc hai instance chạy chồng chỉ cập nhật
 * 0 đơn. Phải gọi trong TenantContext của tenant (job làm việc đó bằng callAs). Không transactional: mỗi lô tự mở
 * transaction qua {@link AnonymizeWalkInBatchUseCase}.
 */
public class AnonymizeWalkInContactsUseCase {

    public static final int DEFAULT_BATCH_SIZE = 500;

    private final GetSalesSettingsUseCase settings;
    private final AnonymizeWalkInBatchUseCase batch;
    private final Clock clock;
    private final int batchSize;

    public AnonymizeWalkInContactsUseCase(GetSalesSettingsUseCase settings, AnonymizeWalkInBatchUseCase batch,
                                          Clock clock, int batchSize) {
        this.settings = settings;
        this.batch = batch;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Trả tổng số đơn đã ẩn danh trong lần chạy này. */
    public int execute(UUID tenantId) {
        Instant now = clock.instant();
        Instant cutoff = RetentionPolicy.cutoff(now, settings.execute(tenantId).walkInPiiRetentionMonths());
        int total = 0;
        while (true) {
            var r = batch.executeBatch(tenantId, cutoff, batchSize, now);
            total += r.changed();
            if (r.changed() == 0 || r.selected() < batchSize) {
                return total;
            }
        }
    }
}
