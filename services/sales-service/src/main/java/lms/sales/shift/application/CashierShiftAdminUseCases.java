package lms.sales.shift.application;

import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shift.domain.CashierShift;
import org.springframework.transaction.annotation.Transactional;

/** Quản lý thao tác trên MỌI ca của tenant: tìm, xem, đóng thay, mở lại, duyệt chênh lệch. */
public class CashierShiftAdminUseCases {

    private final CashierShiftRepository shifts;
    private final ShiftOperations operations;

    public CashierShiftAdminUseCases(CashierShiftRepository shifts, ShiftOperations operations) {
        this.shifts = shifts;
        this.operations = operations;
    }

    @Transactional(readOnly = true)
    public PageResult<ShiftView> search(UUID tenantId, CashierShiftRepository.Criteria criteria, int page, int size) {
        return shifts.search(tenantId, criteria, page, size).map(ShiftView::from);
    }

    @Transactional(readOnly = true)
    public ShiftDetailView get(UUID tenantId, UUID shiftId) {
        return operations.detail(tenantId, load(tenantId, shiftId));
    }

    /** Quản lý đóng thay ca đang mở của thu ngân (ví dụ thu ngân đã về); cùng quy tắc chênh lệch và lý do. */
    @Transactional
    public ShiftView closeOnBehalf(UUID tenantId, UUID actor, UUID shiftId, CashierShiftUseCases.CloseCommand cmd) {
        return ShiftView.from(operations.close(tenantId, actor, load(tenantId, shiftId), cmd.countedCash(),
                cmd.varianceReason()));
    }

    @Transactional
    public ShiftView reopen(UUID tenantId, UUID actor, UUID shiftId, String reason) {
        return ShiftView.from(operations.reopen(tenantId, actor, load(tenantId, shiftId), reason));
    }

    @Transactional
    public ShiftView approveVariance(UUID tenantId, UUID actor, UUID shiftId, String note) {
        return ShiftView.from(operations.approveVariance(tenantId, actor, load(tenantId, shiftId), note));
    }

    private CashierShift load(UUID tenantId, UUID shiftId) {
        return shifts.findById(tenantId, shiftId).orElseThrow(CashierShiftUseCases::notFound);
    }
}
