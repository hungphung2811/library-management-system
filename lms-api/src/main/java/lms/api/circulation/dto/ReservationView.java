package lms.api.circulation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** queuePosition: vị trí trong hàng đợi khi WAITING (1 = đầu hàng), ngược lại 0; copyId/holdUntil chỉ có khi READY. */
public record ReservationView(UUID id, UUID titleId, String status, long queuePosition, Instant reservedAt,
                              UUID copyId, LocalDate holdUntil) {
}
