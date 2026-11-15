package lms.circulation.borrowing.application;

import java.util.Optional;
import java.util.UUID;

/** Cổng sang context reservation (cùng service, dùng tầng application của reservation): bản sao đang giữ cho ai, và nhận đặt trước. */
public interface CopyReservationPort {

    Optional<UUID> holderOf(UUID copyId);

    /** Chuyển đặt trước giữ bản sao này sang FULFILLED nếu thuộc về độc giả. */
    void fulfill(UUID copyId, UUID readerId);
}
