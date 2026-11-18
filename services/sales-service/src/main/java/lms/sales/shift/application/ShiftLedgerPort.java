package lms.sales.shift.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Đọc tiền đã vào ca (bản ghi thu, khoản hoàn, lệnh thanh toán). Adapter đọc bảng bằng truy vấn gom nhóm theo
 * {@code (tenant_id, shift_id)} (số câu SQL cố định, không theo số đơn); context shift không phụ thuộc ordering.
 */
public interface ShiftLedgerPort {

    /**
     * Báo cáo theo phương thức của ca. {@code closedAt} (có thể null) để tách phần phát sinh sau khi chốt (chỉ có thể là
     * cổng thanh toán báo muộn hoặc hoàn tiền ghi sau).
     */
    ShiftReport report(UUID tenantId, UUID shiftId, Instant closedAt);

    /** Số lệnh thu tay còn PENDING và chưa hết hạn của ca (chặn đóng ca). */
    long pendingManualIntents(UUID tenantId, UUID shiftId, Instant now);
}
