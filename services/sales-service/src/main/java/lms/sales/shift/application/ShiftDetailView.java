package lms.sales.shift.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftEventType;

/**
 * Chi tiết ca: thông tin ca, báo cáo theo phương thức và lịch sử. {@code provisionalExpectedCash} chỉ có khi ca đang mở
 * (tạm tính từ tiền đã vào ca); ca đã đóng dùng {@code shift.expectedCash} đã chụp lúc đóng.
 */
public record ShiftDetailView(ShiftView shift, ShiftReport report, Long provisionalExpectedCash,
                              List<EventView> history) {

    public record EventView(UUID id, ShiftEventType type, UUID actorId, Instant occurredAt,
                            Map<String, Object> snapshot) {
        static EventView from(ShiftEvent e) {
            return new EventView(e.id(), e.type(), e.actorId(), e.occurredAt(), e.snapshot());
        }
    }
}
