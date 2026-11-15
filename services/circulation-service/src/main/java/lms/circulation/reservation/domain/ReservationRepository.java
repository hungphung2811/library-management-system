package lms.circulation.reservation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

    /** Lưu và flush ngay; vi phạm tính duy nhất "một đặt trước còn hiệu lực" ném AlreadyReservedException. */
    Reservation save(Reservation reservation);

    Optional<Reservation> findById(UUID id);

    boolean existsActive(UUID readerId, UUID titleId);

    /** Đặt trước của độc giả, mới nhất trước. */
    List<Reservation> findByReader(UUID readerId);

    /** Vị trí trong hàng đợi FIFO của đầu sách (1 = đầu hàng); 0 nếu đặt trước không ở trạng thái WAITING. */
    long queuePosition(Reservation reservation);

    /** Người đứng đầu hàng đợi WAITING của đầu sách (QĐ7), khóa dòng để hai lần giữ đồng thời không chọn trùng người. */
    Optional<Reservation> findNextWaitingForUpdate(UUID titleId);

    /** Các đặt trước READY có hạn giữ trước ngày today. */
    List<Reservation> findHoldExpired(LocalDate today);

    boolean existsReadyForCopy(UUID copyId);

    /** Đặt trước READY đang giữ bản sao này (cho UC06: ai được nhận bản sao RESERVED). */
    Optional<Reservation> findReadyByCopyId(UUID copyId);
}
