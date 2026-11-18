package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.CopyReleasedEvent;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * QĐ6, QĐ9: một bản sao vừa sẵn sàng (nhập kho, sửa xong, trả về, hoặc nhả từ đặt trước hết hạn/bị hủy) thì
 * được giữ ngay cho người đầu hàng đợi trong số ngày theo chính sách của tenant (mặc định 3 ngày; chỉ áp cho lượt
 * giữ mới, hạn giữ đã đặt không đổi). Idempotent theo copyId nên event giao lại không sao.
 */
public class HoldCopyForNextReservationUseCase {

    private final ReservationRepository repository;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;
    private final GetCirculationPolicyUseCase policies;

    public HoldCopyForNextReservationUseCase(ReservationRepository repository, PublishReservationEventPort eventPort,
                                             Clock clock) {
        this(repository, eventPort, clock, GetCirculationPolicyUseCase.defaultsOnly());
    }

    public HoldCopyForNextReservationUseCase(ReservationRepository repository, PublishReservationEventPort eventPort,
                                             Clock clock, GetCirculationPolicyUseCase policies) {
        this.policies = policies;
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Dùng cho event "bản sao sẵn sàng": giữ cho người chờ nếu có; không có thì không làm gì. @return true nếu đã giữ. */
    @Transactional
    public boolean holdIfAnyoneWaiting(UUID titleId, UUID copyId) {
        return holdIfAnyoneWaiting(null, null, titleId, copyId);
    }

    /**
     * ADR-0026: giữ cho người đầu hàng đợi của TÁC PHẨM, chỉ xét độc giả nhận được ngôn ngữ của bản sao (đặt trước không
     * khai ngôn ngữ nhận mọi ngôn ngữ). Event cũ không có workId hoặc không có language thì rơi về hàng đợi theo đúng
     * ấn bản {@code titleId} (hành vi trước ADR-0026).
     */
    @Transactional
    public boolean holdIfAnyoneWaiting(UUID workId, String language, UUID titleId, UUID copyId) {
        if (repository.existsReadyForCopy(copyId)) {
            return true;
        }
        String lang = language == null || language.isBlank() ? null : language.trim().toLowerCase(java.util.Locale.ROOT);
        Optional<Reservation> next = workId != null && lang != null
                ? repository.findNextWaitingInWorkForUpdate(workId, lang)
                : repository.findNextWaitingForUpdate(titleId);
        if (next.isEmpty()) {
            return false;
        }
        Reservation reservation = next.get();
        reservation.markReady(copyId, LocalDate.now(clock), Instant.now(clock), policies.execute().reservationHoldDays());
        repository.save(reservation);
        reservation.pullEvents().forEach(eventPort::publish);
        return true;
    }

    /** Dùng khi bản sao đang giữ bị nhả (hết hạn/hủy): giữ cho người kế tiếp, không có ai thì báo catalog trả về kho. */
    @Transactional
    public void holdOrRelease(UUID titleId, UUID copyId) {
        holdOrRelease(null, null, titleId, copyId);
    }

    /** Như {@link #holdOrRelease(UUID, UUID)} nhưng theo tác phẩm và ngôn ngữ của đặt trước vừa nhả bản sao. */
    @Transactional
    public void holdOrRelease(UUID workId, String language, UUID titleId, UUID copyId) {
        if (!holdIfAnyoneWaiting(workId, language, titleId, copyId)) {
            eventPort.publish(new CopyReleasedEvent(UUID.randomUUID(), copyId, titleId, Instant.now(clock)));
        }
    }
}
