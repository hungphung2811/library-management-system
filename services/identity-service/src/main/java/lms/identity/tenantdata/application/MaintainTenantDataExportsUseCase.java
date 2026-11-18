package lms.identity.tenantdata.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dọn dẹp định kỳ (ADR-0031 D3): gói READY quá hạn thì xóa file và chuyển EXPIRED; yêu cầu RUNNING quá
 * {@code staleAfter} (worker chết) thì FAILED STALE và xóa file dở; sau khởi động lại, RUNNING được đưa về PENDING
 * (không còn worker nào giữ việc; chỉ đúng khi identity chạy MỘT instance như ADR ghi).
 */
public class MaintainTenantDataExportsUseCase {

    private static final Logger log = LoggerFactory.getLogger(MaintainTenantDataExportsUseCase.class);
    private static final int BATCH = 100;

    private final TenantDataExportRepository exports;
    private final ExportStoragePort storage;
    private final ExportPolicy policy;
    private final ExportAuditor auditor;
    private final Clock clock;

    public MaintainTenantDataExportsUseCase(TenantDataExportRepository exports, ExportStoragePort storage,
                                            ExportPolicy policy,
                                            lms.identity.tenant.application.PublishPlatformEventPort events,
                                            Clock clock) {
        this.exports = exports;
        this.storage = storage;
        this.policy = policy;
        this.auditor = new ExportAuditor(events);
        this.clock = clock;
    }

    /** Gọi một lần khi khởi động. */
    public int recoverAfterRestart() {
        int n = exports.requeueRunning();
        if (n > 0) {
            log.warn("Đưa {} yêu cầu xuất RUNNING bị kẹt sau khởi động lại về PENDING", n);
        }
        return n;
    }

    /** @return số gói đã chuyển EXPIRED. */
    public int expireDue() {
        Instant now = clock.instant();
        int count = 0;
        List<TenantDataExport> due = exports.findReadyExpiredBefore(now, BATCH);
        for (TenantDataExport e : due) {
            try {
                if (e.storageKey() != null) {
                    storage.delete(e.storageKey());
                }
                e.expire(now);
                exports.update(e);
                auditor.record(ExportAuditor.EXPIRED, e.requestedBy(), e.requestedByPlatform(), e.tenantId(), e.id(),
                        "expired");
                count++;
            } catch (RuntimeException ex) {
                log.warn("Không hết hạn được export {}: {}", e.id(), ex.toString());
            }
        }
        return count;
    }

    /** @return số yêu cầu bị đánh dấu FAILED STALE. */
    public int failStale() {
        Instant now = clock.instant();
        int count = 0;
        for (TenantDataExport e : exports.findRunningStartedBefore(now.minus(policy.staleAfter()))) {
            try {
                storage.delete(TenantDataExport.storageKeyOf(e.id()));
                storage.deleteByPrefix(RunTenantDataExportUseCase.spoolPrefix(e.id()));
                e.fail("STALE", now);
                exports.update(e);
                auditor.record(ExportAuditor.FAILED, e.requestedBy(), e.requestedByPlatform(), e.tenantId(), e.id(),
                        "STALE");
                count++;
            } catch (RuntimeException ex) {
                log.warn("Không đánh dấu STALE được export {}: {}", e.id(), ex.toString());
            }
        }
        return count;
    }
}
