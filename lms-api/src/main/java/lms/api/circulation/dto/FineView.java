package lms.api.circulation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * amount là số tiền gốc (không đổi); payableAmount là số phải thu sau miễn/giảm (WAIVED là 0). status: UNPAID, PAID,
 * WAIVED, REDUCED. assessment là snapshot cách tính khoản hỏng/mất, null với phí trễ và khoản cũ.
 */
public record FineView(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                       BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId,
                       BigDecimal payableAmount, Assessment assessment) {

    public FineView(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                    BigDecimal amount, String status, Instant createdAt, Instant paidAt, UUID tenantId) {
        this(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt, tenantId, amount, null);
    }

    /** priceSource: COVER_PRICE hoặc PURCHASE_PRICE (nguồn thực dùng); priceFallback=true nếu đã rơi về giá bìa. */
    public record Assessment(BigDecimal basePrice, String priceSource, boolean priceFallback, BigDecimal rate,
                             String damageLevelCode, String damageLevelLabel, BigDecimal amountBeforeClamp,
                             String note, List<String> evidenceUrls) {
    }
}
