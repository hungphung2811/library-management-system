package lms.api.sales.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO ca thu ngân (ADR-0030 gói B), hợp đồng khớp /internal/sales/shifts/** và /internal/sales/shift-settings của
 * sales-service. Tiền là số đồng nguyên. Trường thời điểm là ISO-8601 UTC.
 */
public final class SalesShiftDtos {

    private SalesShiftDtos() {
    }

    // ---- Yêu cầu ----

    /** Mở ca: tiền đầu ca không âm; clientRequestId chống bấm hai lần (UUID hoặc chuỗi tối đa 100 ký tự). */
    public record OpenShiftRequest(@NotNull @Min(0) Long openingFloat, @Size(max = 100) String registerLabel,
                                   @NotBlank @Size(max = 100) String clientRequestId) {
    }

    /** Đóng ca: tiền đếm thực tế; lý do bắt buộc khi chênh lệch vượt ngưỡng (400 SHIFT_VARIANCE_REASON_REQUIRED). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CloseShiftRequest(@NotNull @Min(0) Long countedCash, @Size(max = 500) String varianceReason) {
    }

    /** Mở lại ca: lý do bắt buộc (400 REOPEN_REASON_REQUIRED, kiểm ở sales-service). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReopenShiftRequest(@Size(max = 500) String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApproveVarianceRequest(@Size(max = 500) String note) {
    }

    public record UpdateShiftSettingsRequest(@NotNull Boolean requireOpenShiftForPos,
                                             @NotNull @Min(0) Long varianceThreshold, @NotNull @Min(0) Long version) {
    }

    // ---- Truy vấn (gom thành record) ----

    /** Ca của tôi: lọc theo trạng thái (OPEN, CLOSED). */
    public record MyShiftsQuery(String status, Integer page, Integer size) {
    }

    /** Mọi ca (quản lý): lọc theo thu ngân, trạng thái ca, trạng thái duyệt chênh lệch, khoảng thời gian mở ca. */
    public record ShiftSearchQuery(UUID cashierUserId, String status, String varianceStatus, Instant from, Instant to,
                                   Integer page, Integer size) {
    }

    // ---- Phản hồi ----

    /**
     * Ca thu ngân. {@code phase}: OPEN, REOPENED, PENDING_APPROVAL (đã đóng, chênh lệch chờ duyệt), CLOSED.
     * {@code status}: OPEN hoặc CLOSED. {@code varianceStatus}: NONE, PENDING_APPROVAL, APPROVED. Số liệu đóng ca
     * (countedCash, expectedCash, variance) null khi ca đang mở.
     */
    public record ShiftView(UUID id, String shiftNo, UUID cashierUserId, String registerLabel, String status,
                            String phase, Instant openedAt, long openingFloat, Instant closedAt, UUID closedBy,
                            Long countedCash, Long expectedCash, Long variance, String varianceReason,
                            String varianceStatus, UUID approvedBy, Instant approvedAt, String approvalNote,
                            int reopenCount, long version) {
    }

    /**
     * Chi tiết ca: báo cáo theo phương thức (luôn đủ CASH, CARD, BANK_TRANSFER, QR, MOMO, SEPAY) và lịch sử.
     * {@code provisionalExpectedCash} chỉ có khi ca đang mở (tạm tính); ca đã đóng dùng {@code shift.expectedCash}.
     */
    public record ShiftDetailView(ShiftView shift, ShiftReportView report, Long provisionalExpectedCash,
                                  List<ShiftEventView> history) {
    }

    public record ShiftReportView(long orderCount, long grandTotal, long discountTotal,
                                  List<ShiftMethodLineView> methods, long collectedTotal, long refundedTotal,
                                  long netTotal) {
    }

    /** Một phương thức trong báo cáo; các trường AfterClose là phần phát sinh sau thời điểm chốt (đã nằm trong tổng). */
    public record ShiftMethodLineView(String method, long paymentCount, long collected, long refundCount,
                                      long refunded, long net, long paymentCountAfterClose, long collectedAfterClose,
                                      long refundCountAfterClose, long refundedAfterClose) {
    }

    public record ShiftEventView(UUID id, String type, UUID actorId, Instant occurredAt, Map<String, Object> snapshot) {
    }

    public record ShiftSettingsView(boolean requireOpenShiftForPos, long varianceThreshold, long version,
                                    Instant updatedAt) {
    }
}
