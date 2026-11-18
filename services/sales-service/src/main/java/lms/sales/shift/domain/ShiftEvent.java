package lms.sales.shift.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Một dòng lịch sử ca: người thao tác, thời điểm và ảnh chụp số liệu (số tiền, lý do). */
public record ShiftEvent(UUID id, UUID shiftId, ShiftEventType type, UUID actorId, Instant occurredAt,
                         Map<String, Object> snapshot) {
    public ShiftEvent {
        snapshot = snapshot == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }
}
