package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * queuePosition là vị trí trong hàng đợi (1 = đầu hàng) khi đang WAITING, ngược lại là 0.
 * copyId và holdUntil chỉ có khi READY (bản sao đang giữ cho độc giả, hạn nhận).
 */
public record ReservationResponse(UUID id, UUID titleId, ReservationStatus status, long queuePosition,
                                  Instant reservedAt, UUID copyId, LocalDate holdUntil, UUID workId, String language) {

    /** Dạng 7 trường cũ (chưa có workId, language). */
    public ReservationResponse(UUID id, UUID titleId, ReservationStatus status, long queuePosition,
                               Instant reservedAt, UUID copyId, LocalDate holdUntil) {
        this(id, titleId, status, queuePosition, reservedAt, copyId, holdUntil, null, null);
    }

    public static ReservationResponse from(Reservation r, long queuePosition) {
        boolean ready = r.status() == ReservationStatus.READY;
        return new ReservationResponse(r.id(), r.titleId(), r.status(), queuePosition, r.reservedAt(),
                ready ? r.copyId() : null, ready ? r.holdUntil() : null, r.workId(), r.language());
    }
}
