package lms.sales.shift.application;

import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.domain.CashierShift;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link ShiftAttributionPort}: tra ca mở của người thao tác, đọc cờ tenant chỉ khi không có ca. */
public class ShiftAttributionService implements ShiftAttributionPort {

    private final CashierShiftRepository shifts;
    private final ShiftSettingsPort settings;

    public ShiftAttributionService(CashierShiftRepository shifts, ShiftSettingsPort settings) {
        this.shifts = shifts;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public UUID resolve(UUID tenantId, UUID userId, boolean enforceWhenRequired) {
        UUID shiftId = userId == null ? null : shifts.findOpenByCashier(tenantId, userId)
                .map(CashierShift::id).orElse(null);
        if (shiftId == null && enforceWhenRequired && requireOpenShift(tenantId)) {
            throw new SalesException(ErrorCode.SHIFT_NOT_OPEN, "Chưa mở ca thu ngân");
        }
        return shiftId;
    }

    private boolean requireOpenShift(UUID tenantId) {
        return settings.find(tenantId).map(s -> s.requireOpenShiftForPos()).orElse(false);
    }
}
