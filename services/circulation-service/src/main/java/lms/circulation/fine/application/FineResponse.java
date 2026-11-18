package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * amount là số tiền gốc (không đổi); payableAmount là số phải thu sau miễn/giảm (WAIVED là 0). status: UNPAID, PAID,
 * WAIVED, REDUCED. assessment là snapshot cách tính khoản hỏng/mất (null với phí trễ và khoản cũ). tenantId: tenant sở
 * hữu khoản phạt (ADR-0008); null ở phản hồi của thao tác ghi (khoản vừa lập chưa đọc lại từ DB).
 */
public record FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                           BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId,
                           BigDecimal payableAmount, Assessment assessment) {

    public FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                        BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId) {
        this(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt, tenantId, amount, null);
    }

    public FineResponse(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                        BigDecimal amount, String status, Instant createdAt, Instant paidAt) {
        this(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt, null);
    }

    /**
     * priceSource là nguồn giá thực dùng (COVER_PRICE hoặc PURCHASE_PRICE); priceFallback=true nếu đã rơi về giá bìa
     * vì bản sao không có giá mua. damageLevelCode/Label null ở khoản mất. note, evidenceUrls do thủ thư nhập lúc trả.
     */
    public record Assessment(BigDecimal basePrice, String priceSource, boolean priceFallback, BigDecimal rate,
                             String damageLevelCode, String damageLevelLabel, BigDecimal amountBeforeClamp,
                             String note, List<String> evidenceUrls) {

        static Assessment from(FineAssessment a) {
            return a == null ? null : new Assessment(a.basePrice(), a.priceSource() == null ? null : a.priceSource().name(),
                    a.priceFallback(), a.rate(), a.damageLevelCode(), a.damageLevelLabel(), a.amountBeforeClamp(),
                    a.note(), a.evidenceUrls());
        }
    }

    public static FineResponse from(Fine f) {
        return new FineResponse(f.id(), f.readerId(), f.borrowingId(), f.bookCopyId(), f.reason().name(),
                f.amount(), f.status().name(), f.createdAt(), f.paidAt(), f.tenantId(), f.payableAmount(),
                Assessment.from(f.assessment()));
    }
}
