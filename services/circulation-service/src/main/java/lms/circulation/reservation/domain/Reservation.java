package lms.circulation.reservation.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: một đặt trước trong hàng đợi của đầu sách. Hàng đợi sắp theo thời điểm đặt (QĐ7).
 * WAITING -> READY (được giữ một bản sao trong 3 ngày, QĐ6) -> FULFILLED | EXPIRED | CANCELLED.
 */
public class Reservation {

    /** QĐ6: số ngày giữ bản sao cho người đặt trước. */
    public static final int HOLD_DAYS = 3;

    private final UUID id;
    private final UUID readerId;
    private final UUID titleId;
    /** Tác phẩm của ấn bản lúc đặt (ADR-0026); đổi khi ấn bản chuyển tác phẩm. Luôn khác null. */
    private UUID workId;
    /** Ngôn ngữ ấn bản độc giả chọn; null = mọi ngôn ngữ. Hàng đợi/gán bản sao chỉ xét bản sao cùng ngôn ngữ. */
    private final String language;
    private final Instant reservedAt;
    private ReservationStatus status;
    private UUID copyId;
    private LocalDate holdUntil;
    private final List<DomainEvent> events = new ArrayList<>();

    private Reservation(UUID id, UUID readerId, UUID titleId, UUID workId, String language, Instant reservedAt,
                        ReservationStatus status, UUID copyId, LocalDate holdUntil) {
        if (id == null) throw new InvalidReservationException("id không được null");
        if (readerId == null) throw new InvalidReservationException("readerId không được null");
        if (titleId == null) throw new InvalidReservationException("titleId không được null");
        if (reservedAt == null) throw new InvalidReservationException("reservedAt không được null");
        if (status == null) throw new InvalidReservationException("status không được null");
        if (status == ReservationStatus.READY && (copyId == null || holdUntil == null)) {
            throw new InvalidReservationException("Đặt trước READY phải có bản sao được giữ và hạn giữ");
        }
        this.id = id;
        this.readerId = readerId;
        this.titleId = titleId;
        this.workId = workId == null ? LegacyWorkIds.ofTitle(titleId) : workId;
        this.language = language == null || language.isBlank() ? null : language.trim().toLowerCase(java.util.Locale.ROOT);
        this.reservedAt = reservedAt;
        this.status = status;
        this.copyId = copyId;
        this.holdUntil = holdUntil;
    }

    /** UC03: tạo đặt trước ở cuối hàng đợi (WAITING). Việc kiểm tra điều kiện (QĐ2, QĐ5) do use case đảm nhiệm. */
    public static Reservation create(UUID readerId, UUID titleId, Instant now) {
        return create(readerId, titleId, null, null, now);
    }

    /** Đặt trước theo tác phẩm {@code workId} (null = tác phẩm 1:1 tất định của ấn bản), cùng ngôn ngữ {@code language}. */
    public static Reservation create(UUID readerId, UUID titleId, UUID workId, String language, Instant now) {
        Reservation r = new Reservation(UUID.randomUUID(), readerId, titleId, workId, language, now,
                ReservationStatus.WAITING, null, null);
        r.events.add(new ReservationCreatedEvent(UUID.randomUUID(), r.id, readerId, titleId, now));
        return r;
    }

    /** Dựng lại aggregate từ persistence, không phát sinh event. */
    public static Reservation restore(UUID id, UUID readerId, UUID titleId, Instant reservedAt,
                                      ReservationStatus status, UUID copyId, LocalDate holdUntil) {
        return new Reservation(id, readerId, titleId, null, null, reservedAt, status, copyId, holdUntil);
    }

    public static Reservation restore(UUID id, UUID readerId, UUID titleId, UUID workId, String language,
                                      Instant reservedAt, ReservationStatus status, UUID copyId, LocalDate holdUntil) {
        return new Reservation(id, readerId, titleId, workId, language, reservedAt, status, copyId, holdUntil);
    }

    public static Reservation restore(UUID id, UUID readerId, UUID titleId, Instant reservedAt,
                                      ReservationStatus status) {
        return new Reservation(id, readerId, titleId, null, null, reservedAt, status, null, null);
    }

    public boolean isActive() {
        return status == ReservationStatus.WAITING || status == ReservationStatus.READY;
    }

    public boolean isOwnedBy(UUID userId) {
        return readerId.equals(userId);
    }

    /** True nếu đang giữ bản sao và đã quá hạn giữ (QĐ6): hạn giữ là ngày cuối cùng còn được nhận. */
    public boolean isHoldExpired(LocalDate today) {
        return status == ReservationStatus.READY && holdUntil.isBefore(today);
    }

    /** QĐ6, QĐ9: giữ bản sao cho đặt trước đứng đầu hàng đợi, hạn giữ 3 ngày. */
    public void markReady(UUID heldCopyId, LocalDate today, Instant now) {
        markReady(heldCopyId, today, now, HOLD_DAYS);
    }

    /** Như {@link #markReady(UUID, LocalDate, Instant)} với số ngày giữ chỗ theo chính sách của tenant. */
    public void markReady(UUID heldCopyId, LocalDate today, Instant now, int holdDays) {
        if (holdDays <= 0) throw new InvalidReservationException("Số ngày giữ chỗ phải > 0");
        if (status != ReservationStatus.WAITING) {
            throw new InvalidReservationException("Chỉ đặt trước WAITING mới được giữ bản sao (" + status + ")");
        }
        if (heldCopyId == null) throw new InvalidReservationException("copyId không được null");
        this.status = ReservationStatus.READY;
        this.copyId = heldCopyId;
        this.holdUntil = today.plusDays(holdDays);
        events.add(new ReservationReadyEvent(UUID.randomUUID(), id, readerId, titleId, heldCopyId, holdUntil, now));
    }

    /** UC14: quá hạn giữ thì chuyển EXPIRED. Bản sao đã giữ do use case xử lý tiếp (giữ cho người kế tiếp hoặc trả về kho). */
    public void expire(LocalDate today, Instant now) {
        if (!isHoldExpired(today)) {
            throw new InvalidReservationException("Đặt trước chưa quá hạn giữ");
        }
        status = ReservationStatus.EXPIRED;
        events.add(new ReservationExpiredEvent(UUID.randomUUID(), id, readerId, titleId, copyId, now));
    }

    /** UC06 (4c): độc giả nhận đúng bản sao được giữ cho mình. READY -> FULFILLED. */
    public void fulfill(Instant now) {
        if (status != ReservationStatus.READY) {
            throw new InvalidReservationException("Chỉ đặt trước READY mới nhận được bản sao (" + status + ")");
        }
        status = ReservationStatus.FULFILLED;
        events.add(new ReservationFulfilledEvent(UUID.randomUUID(), id, readerId, titleId, copyId, now));
    }

    /** UC04: rút khỏi hàng đợi. Chỉ đặt trước còn hiệu lực (WAITING, READY) mới hủy được. */
    public void cancel(Instant now) {
        if (!isActive()) {
            throw new InvalidReservationException("Đặt trước không còn hiệu lực (" + status + "), không thể hủy");
        }
        status = ReservationStatus.CANCELLED;
        events.add(new ReservationCancelledEvent(UUID.randomUUID(), id, readerId, titleId, copyId, now));
    }

    /** ADR-0026: ấn bản của đặt trước vừa đổi tác phẩm (tách/gộp). Chỉ đặt trước còn hiệu lực mới chuyển. */
    public void moveToWork(UUID newWorkId) {
        if (newWorkId == null) throw new InvalidReservationException("workId không được null");
        if (!isActive()) {
            throw new InvalidReservationException("Đặt trước không còn hiệu lực (" + status + "), không chuyển tác phẩm");
        }
        this.workId = newWorkId;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public UUID id() { return id; }
    public UUID readerId() { return readerId; }
    public UUID titleId() { return titleId; }
    public UUID workId() { return workId; }
    /** Ngôn ngữ ấn bản đã chọn; null = mọi ngôn ngữ. */
    public String language() { return language; }
    public Instant reservedAt() { return reservedAt; }
    public ReservationStatus status() { return status; }
    /** Bản sao đang/đã được giữ; null khi chưa từng được giữ. */
    public UUID copyId() { return copyId; }
    public LocalDate holdUntil() { return holdUntil; }
}
