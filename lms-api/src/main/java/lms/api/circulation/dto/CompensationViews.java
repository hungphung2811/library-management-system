package lms.api.circulation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTO chuyển tiếp của chính sách đền bù, bậc hỏng, miễn/giảm và hoàn khoản (cùng hình dạng JSON với circulation-service). */
public final class CompensationViews {

    private CompensationViews() {
    }

    /** custom=false: tenant chưa cấu hình (giá trị mặc định, version 0). minAmount/maxAmount null = không giới hạn. */
    public record CompensationPolicy(BigDecimal lostRate, String priceSource, BigDecimal minAmount, BigDecimal maxAmount,
                                     int foundWindowDays, BigDecimal refundProcessingFee, boolean custom, long version,
                                     Instant updatedAt) {
    }

    public record UpdateCompensationPolicy(@NotNull BigDecimal lostRate,
                                           @NotNull @Pattern(regexp = "COVER_PRICE|PURCHASE_PRICE") String priceSource,
                                           BigDecimal minAmount, BigDecimal maxAmount, @NotNull Integer foundWindowDays,
                                           BigDecimal refundProcessingFee, Long version) {
    }

    public record DamageLevel(String code, String label, BigDecimal rate, int sortOrder, boolean active, long version,
                              Instant updatedAt) {
    }

    /** custom=false: items là hạt giống mặc định chưa ghi vào DB. */
    public record DamageLevelList(boolean custom, int maxLevels, List<DamageLevel> items) {
    }

    public record CreateDamageLevel(@NotBlank String code, @NotBlank String label, @NotNull BigDecimal rate,
                                    Integer sortOrder, Boolean active) {
    }

    public record UpdateDamageLevel(String code, @NotBlank String label, @NotNull BigDecimal rate,
                                    @NotNull Integer sortOrder, @NotNull Boolean active, Long version) {
    }

    /** type: WAIVE (miễn toàn phần) hoặc REDUCE (giảm, payableAmount là số phải thu mới). */
    public record CreateAdjustment(@NotNull @Pattern(regexp = "WAIVE|REDUCE") String type, BigDecimal payableAmount,
                                   @NotBlank @Size(max = 500) String reason) {
    }

    public record Decision(@Size(max = 500) String note) {
    }

    /** Bộ lọc danh sách đề nghị miễn/giảm (mọi trường tùy chọn). status: PENDING, APPROVED, REJECTED. */
    public record AdjustmentSearch(String status, UUID fineId, UUID readerId, Integer page, Integer size) {
    }

    public record RefundSearch(String status, UUID readerId, Integer page, Integer size) {
    }

    public record Adjustment(UUID id, UUID fineId, UUID readerId, String fineReason, BigDecimal fineAmount, String type,
                             BigDecimal payableAmount, String reason, String status, UUID requestedBy,
                             Instant requestedAt, UUID decidedBy, Instant decidedAt, String decisionNote, UUID tenantId) {
    }

    public record Refund(UUID id, UUID fineId, UUID borrowingId, UUID bookCopyId, UUID readerId, LocalDate lostOn,
                         LocalDate foundOn, String restoreStatus, String checkNote, String refundMethod,
                         String referenceCode, String mode, String fineStatusAtRequest, BigDecimal collectedAmount,
                         BigDecimal processingFee, BigDecimal refundAmount, String status, UUID requestedBy,
                         Instant requestedAt, UUID decidedBy, Instant decidedAt, String decisionNote, UUID tenantId) {
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    /** Ghi nhận tìm lại sách đã báo mất: restoreStatus AVAILABLE|DAMAGED, refundMethod CASH|BANK_TRANSFER. */
    public record ReportFound(UUID bookCopyId, String barcode,
                              @NotNull @Pattern(regexp = "AVAILABLE|DAMAGED") String restoreStatus,
                              @Size(max = 500) String checkNote,
                              @NotNull @Pattern(regexp = "CASH|BANK_TRANSFER") String refundMethod,
                              @Size(max = 100) String referenceCode) {
    }
}
