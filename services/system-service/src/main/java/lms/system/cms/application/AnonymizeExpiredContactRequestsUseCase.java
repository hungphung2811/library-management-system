package lms.system.cms.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lms.system.cms.domain.ContactRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ẩn danh yêu cầu liên hệ quá hạn lưu giữ (ADR-0011 mục 2.10), mỗi lần một lô trong một transaction; người gọi
 * (job) lặp tới khi hết. Idempotent: yêu cầu đã ẩn danh không bao giờ được chọn lại, chạy chồng nhiều instance chỉ
 * cập nhật 0 dòng ở lần sau. Áp cho mọi trạng thái kể cả NEW.
 */
public class AnonymizeExpiredContactRequestsUseCase {

    private final ContactRequestRepositoryPort repository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public AnonymizeExpiredContactRequestsUseCase(ContactRequestRepositoryPort repository, Clock clock,
                                                  Duration retention, int batchSize) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Thời hạn lưu giữ phải dương");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("Cỡ lô phải từ 1");
        }
        this.repository = repository;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    public int batchSize() {
        return batchSize;
    }

    /** Ẩn danh một lô; trả số dòng đã ẩn danh (nhỏ hơn {@link #batchSize()} nghĩa là đã hết). */
    @Transactional
    public int anonymizeBatch() {
        Instant now = clock.instant();
        List<ContactRequest> due = repository.findDueForAnonymization(now.minus(retention), batchSize);
        for (ContactRequest request : due) {
            repository.save(request.anonymize(now));
        }
        return due.size();
    }
}
