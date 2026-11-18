package lms.sales.shift.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Cấu hình ca thu ngân theo tenant (ADR-0030): cờ chặn bán khi chưa mở ca (mặc định tắt) và ngưỡng chênh lệch tiền mặt
 * được bỏ qua lý do (mặc định 0 đồng nghĩa mọi chênh lệch khác 0 đều cần lý do và duyệt). Tạo lười như chính sách khác.
 */
public final class ShiftSettings {

    public static final long MAX_THRESHOLD = 1_000_000_000L;

    private final UUID tenantId;
    private final boolean requireOpenShiftForPos;
    private final long varianceThreshold;
    private final long version;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private ShiftSettings(UUID tenantId, boolean requireOpenShiftForPos, long varianceThreshold, long version,
                          Instant updatedAt, UUID updatedBy) {
        if (varianceThreshold < 0 || varianceThreshold > MAX_THRESHOLD) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS,
                    "Ngưỡng chênh lệch phải trong 0 đến " + MAX_THRESHOLD + " đồng");
        }
        this.tenantId = tenantId;
        this.requireOpenShiftForPos = requireOpenShiftForPos;
        this.varianceThreshold = varianceThreshold;
        this.version = version;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** Chưa lưu: version 0, cờ tắt, ngưỡng 0. */
    public static ShiftSettings defaults(UUID tenantId) {
        return new ShiftSettings(tenantId, false, 0, 0, null, null);
    }

    public static ShiftSettings restore(UUID tenantId, boolean requireOpenShiftForPos, long varianceThreshold,
                                        long version, Instant updatedAt, UUID updatedBy) {
        return new ShiftSettings(tenantId, requireOpenShiftForPos, varianceThreshold, version, updatedAt, updatedBy);
    }

    /** Bản mới (cùng version; adapter tăng version khi lưu). */
    public ShiftSettings update(boolean requireOpenShiftForPos, long varianceThreshold, Instant now, UUID actor) {
        return new ShiftSettings(tenantId, requireOpenShiftForPos, varianceThreshold, version, now, actor);
    }

    public UUID tenantId() { return tenantId; }
    public boolean requireOpenShiftForPos() { return requireOpenShiftForPos; }
    public long varianceThreshold() { return varianceThreshold; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}
