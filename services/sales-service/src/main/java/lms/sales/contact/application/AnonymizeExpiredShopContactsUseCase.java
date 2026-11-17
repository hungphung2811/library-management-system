package lms.sales.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.domain.RetentionPolicy;

/**
 * Ẩn danh liên hệ hết hạn lưu giữ của MỘT tenant theo {@code walkInPiiRetentionMonths} (ADR-0015 mục 2.4), mốc tính
 * theo created_at, mọi trạng thái. Theo lô (mặc định 500 dòng/transaction). Idempotent. Gọi trong TenantContext của
 * tenant; không transactional, mỗi lô tự mở transaction qua {@link AnonymizeShopContactBatchUseCase}.
 */
public class AnonymizeExpiredShopContactsUseCase {

    public static final int DEFAULT_BATCH_SIZE = 500;

    private final GetSalesSettingsUseCase settings;
    private final AnonymizeShopContactBatchUseCase batch;
    private final Clock clock;
    private final int batchSize;

    public AnonymizeExpiredShopContactsUseCase(GetSalesSettingsUseCase settings,
                                               AnonymizeShopContactBatchUseCase batch, Clock clock, int batchSize) {
        this.settings = settings;
        this.batch = batch;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Trả tổng số yêu cầu đã ẩn danh trong lần chạy này. */
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
