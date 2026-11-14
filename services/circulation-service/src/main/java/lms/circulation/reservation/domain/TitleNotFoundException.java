package lms.circulation.reservation.domain;

import java.util.UUID;

public class TitleNotFoundException extends RuntimeException {
    public TitleNotFoundException(UUID titleId) {
        super("Không tìm thấy đầu sách: " + titleId);
    }
}
