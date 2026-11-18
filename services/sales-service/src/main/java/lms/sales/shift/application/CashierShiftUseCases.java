package lms.sales.shift.application;

import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thu ngân thao tác ca CỦA MÌNH: mở, xem ca đang mở (kèm số liệu tạm tính), danh sách và chi tiết ca của mình, đóng ca.
 * Quyền sở hữu kiểm ở đây theo {@code userId} của token (ADR-0003 chỉ kiểm khóa theo endpoint): ca của người khác trả
 * 404 SHIFT_NOT_FOUND cùng thân với ca không tồn tại, không lộ sự tồn tại của ca.
 */
public class CashierShiftUseCases {

    public record OpenCommand(long openingFloat, String registerLabel, UUID clientRequestId) {
    }

    public record CloseCommand(long countedCash, String varianceReason) {
    }

    private final CashierShiftRepository shifts;
    private final ShiftOperations operations;

    public CashierShiftUseCases(CashierShiftRepository shifts, ShiftOperations operations) {
        this.shifts = shifts;
        this.operations = operations;
    }

    @Transactional
    public ShiftView open(UUID tenantId, UUID cashier, OpenCommand cmd) {
        requireUser(cashier);
        return ShiftView.from(operations.open(tenantId, cashier, cmd.openingFloat(), cmd.registerLabel(),
                cmd.clientRequestId()));
    }

    /** Ca đang mở của tôi kèm báo cáo tạm tính; không có ca mở thì 404 SHIFT_NOT_FOUND. */
    @Transactional(readOnly = true)
    public ShiftDetailView current(UUID tenantId, UUID cashier) {
        requireUser(cashier);
        return operations.detail(tenantId, shifts.findOpenByCashier(tenantId, cashier)
                .orElseThrow(CashierShiftUseCases::notFound));
    }

    @Transactional(readOnly = true)
    public PageResult<ShiftView> mine(UUID tenantId, UUID cashier, ShiftStatus status, int page, int size) {
        requireUser(cashier);
        return shifts.search(tenantId, new CashierShiftRepository.Criteria(cashier, status, null, null, null), page,
                size).map(ShiftView::from);
    }

    @Transactional(readOnly = true)
    public ShiftDetailView getMine(UUID tenantId, UUID cashier, UUID shiftId) {
        requireUser(cashier);
        return operations.detail(tenantId, owned(tenantId, cashier, shiftId));
    }

    /** Đóng ca đang mở của tôi. Không có ca mở thì 404 SHIFT_NOT_FOUND. */
    @Transactional
    public ShiftView close(UUID tenantId, UUID cashier, CloseCommand cmd) {
        requireUser(cashier);
        CashierShift shift = shifts.findOpenByCashier(tenantId, cashier).orElseThrow(CashierShiftUseCases::notFound);
        return ShiftView.from(operations.close(tenantId, cashier, shift, cmd.countedCash(), cmd.varianceReason()));
    }

    private CashierShift owned(UUID tenantId, UUID cashier, UUID shiftId) {
        return shifts.findById(tenantId, shiftId).filter(s -> s.isOwnedBy(cashier))
                .orElseThrow(CashierShiftUseCases::notFound);
    }

    private static void requireUser(UUID user) {
        if (user == null) {
            throw SalesException.invalidRequest("Thiếu thông tin người dùng đăng nhập");
        }
    }

    static SalesException notFound() {
        return new SalesException(ErrorCode.SHIFT_NOT_FOUND, "Không có ca thu ngân");
    }
}
