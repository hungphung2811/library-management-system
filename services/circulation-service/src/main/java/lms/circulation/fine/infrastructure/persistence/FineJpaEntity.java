package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAssessment;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.PriceSourceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/**
 * Phí phạt thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB
 * (migration circulation V6). Mọi truy vấn của SpringDataFineRepository là JPQL/derived query, không có SQL native.
 * Cột payable_amount NULL (khoản cũ trước V11) nghĩa là bằng amount; snapshot cách tính NULL với phí trễ và khoản cũ.
 */
@Entity
@Table(name = "fines")
class FineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "reader_id", nullable = false)
    private UUID readerId;

    @Column(name = "borrowing_id", nullable = false)
    private UUID borrowingId;

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineReason reason;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payable_amount", precision = 14, scale = 2)
    private BigDecimal payableAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "base_price", precision = 14, scale = 2)
    private BigDecimal basePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", length = 20)
    private PriceSourceKind priceSource;

    @Column(name = "price_fallback")
    private Boolean priceFallback;

    @Column(precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(name = "damage_level_code", length = 30)
    private String damageLevelCode;

    @Column(name = "damage_level_label", length = 100)
    private String damageLevelLabel;

    @Column(name = "amount_before_clamp", precision = 14, scale = 2)
    private BigDecimal amountBeforeClamp;

    @Column(name = "assessment_note", length = 1000)
    private String assessmentNote;

    /** Tối đa 3 URL nối bằng xuống dòng (URL không chứa khoảng trắng). */
    @Column(name = "evidence_urls", length = 1600)
    private String evidenceUrls;

    protected FineJpaEntity() {
    }

    FineJpaEntity(Fine f) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng, kể cả khi thu phạt cập nhật bản ghi cũ) không ghi đè
        // tenant_id thành null; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = f.id();
        this.readerId = f.readerId();
        this.borrowingId = f.borrowingId();
        this.bookCopyId = f.bookCopyId();
        this.reason = f.reason();
        this.amount = f.amount();
        this.payableAmount = f.payableAmount();
        this.status = f.status();
        this.createdAt = f.createdAt();
        this.paidAt = f.paidAt();
        FineAssessment a = f.assessment();
        if (a != null) {
            this.basePrice = a.basePrice();
            this.priceSource = a.priceSource();
            this.priceFallback = a.priceFallback();
            this.rate = a.rate();
            this.damageLevelCode = a.damageLevelCode();
            this.damageLevelLabel = a.damageLevelLabel();
            this.amountBeforeClamp = a.amountBeforeClamp();
            this.assessmentNote = a.note();
            this.evidenceUrls = a.evidenceUrls().isEmpty() ? null : String.join("\n", a.evidenceUrls());
        }
    }

    Fine toDomain() {
        FineAssessment assessment = null;
        if (basePrice != null) {
            List<String> urls = evidenceUrls == null || evidenceUrls.isBlank() ? List.of() : List.of(evidenceUrls.split("\n"));
            assessment = new FineAssessment(basePrice, priceSource, Boolean.TRUE.equals(priceFallback), rate,
                    damageLevelCode, damageLevelLabel, amountBeforeClamp, assessmentNote, urls);
        }
        return Fine.restore(id, readerId, borrowingId, bookCopyId, reason, amount, payableAmount, status, createdAt,
                paidAt, assessment, tenantId);
    }
}
