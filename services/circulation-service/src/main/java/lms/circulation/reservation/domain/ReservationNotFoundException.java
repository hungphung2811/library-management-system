package lms.circulation.reservation.domain;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(UUID id) {
        super("Không tìm thấy đặt trước: " + id);
    }
}
