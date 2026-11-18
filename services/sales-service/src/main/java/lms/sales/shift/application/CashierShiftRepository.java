package lms.sales.shift.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;

/** Lưu ca và lịch sử ca. Adapter dịch vi phạm ràng buộc DB thành {@code SalesException} của miền ca. */
public interface CashierShiftRepository {

    /**
     * Thêm mới hoặc cập nhật (khóa lạc quan theo version, lệch thì 409 SHIFT_STATE_CONFLICT). Chèn ca OPEN thứ hai của
     * cùng thu ngân (hoặc trùng clientRequestId do hai yêu cầu chạy đồng thời) thì 409 SHIFT_ALREADY_OPEN.
     */
    CashierShift save(CashierShift shift);

    Optional<CashierShift> findById(UUID tenantId, UUID id);

    Optional<CashierShift> findOpenByCashier(UUID tenantId, UUID cashierUserId);

    Optional<CashierShift> findByClientRequestId(UUID tenantId, UUID clientRequestId);

    /** Lọc theo các trường khác null; sắp xếp mới nhất trước. */
    PageResult<CashierShift> search(UUID tenantId, Criteria criteria, int page, int size);

    void appendEvent(UUID tenantId, ShiftEvent event);

    List<ShiftEvent> events(UUID tenantId, UUID shiftId);

    record Criteria(UUID cashierUserId, ShiftStatus status, VarianceStatus varianceStatus, Instant from, Instant to) {
    }
}
