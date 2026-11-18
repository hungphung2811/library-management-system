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

    /** Độc giả đã có đặt trước còn hiệu lực (WAITING/READY) trên tác phẩm này (ADR-0026: một độc giả một đặt trước/tác phẩm). */
    boolean existsActiveInWork(UUID readerId, UUID workId);

    /** Đặt trước còn hiệu lực của một ấn bản (dùng khi ấn bản đổi tác phẩm). */
    List<Reservation> findActiveByTitle(UUID titleId);

    /** Đặt trước còn hiệu lực trên tác phẩm của các độc giả này, một truy vấn (gộp tác phẩm, tránh N+1). */
    List<Reservation> findActiveInWorkByReaders(UUID workId, java.util.Collection<UUID> readerIds);

    /**
     * Người đứng đầu hàng đợi WAITING của tác phẩm, chỉ xét đặt trước nhận được bản sao ngôn ngữ {@code language}
     * (đặt trước không khai ngôn ngữ nhận mọi ngôn ngữ). Khóa dòng như {@link #findNextWaitingForUpdate}.
     */
    Optional<Reservation> findNextWaitingInWorkForUpdate(UUID workId, String language);

    /** Đặt trước của độc giả, mới nhất trước. */
    List<Reservation> findByReader(UUID readerId);

    /** Vị trí trong hàng đợi FIFO của tác phẩm + ngôn ngữ của đặt trước (1 = đầu hàng); 0 nếu đặt trước không ở trạng thái WAITING. */
    long queuePosition(Reservation reservation);

    /**
     * Vị trí hàng đợi của nhiều đặt trước bằng một truy vấn (tránh gọi {@link #queuePosition} từng dòng); khóa là id
     * đặt trước, giá trị theo cùng quy ước (0 nếu không WAITING).
     */
    java.util.Map<UUID, Long> queuePositions(java.util.Collection<Reservation> reservations);

    /** Dự phòng cho event cũ không có workId/language: người đứng đầu hàng đợi WAITING của đúng ấn bản (QĐ7), khóa dòng để hai lần giữ đồng thời không chọn trùng người. */
    Optional<Reservation> findNextWaitingForUpdate(UUID titleId);

    /** Các đặt trước READY có hạn giữ trước ngày today. */
    List<Reservation> findHoldExpired(LocalDate today);

    boolean existsReadyForCopy(UUID copyId);

    /** Đặt trước READY đang giữ bản sao này (cho UC06: ai được nhận bản sao RESERVED). */
    Optional<Reservation> findReadyByCopyId(UUID copyId);
}
