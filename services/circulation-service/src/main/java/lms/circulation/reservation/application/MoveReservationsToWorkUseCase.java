package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0026 mục 3.5: một ấn bản vừa đổi tác phẩm (tách hoặc gộp) thì đặt trước còn hiệu lực của ấn bản đó chuyển sang tác
 * phẩm mới (EditionWorkChangedEvent). Idempotent: đặt trước đã ở tác phẩm mới thì bỏ qua nên event giao lại không sao.
 * Gộp có thể làm một độc giả có hai đặt trước còn hiệu lực trên cùng tác phẩm (vi phạm ràng buộc duy nhất): giữ cái đang
 * giữ bản sao (READY) hơn WAITING, cùng trạng thái thì giữ cái đặt sớm hơn, hủy cái còn lại (phát
 * ReservationCancelledEvent để thông báo; bản sao đang giữ của cái bị hủy được giữ cho người kế tiếp hoặc trả về kho).
 * Không gọi truy vấn trong vòng lặp: đặt trước xung đột của mọi độc giả nạp bằng một truy vấn.
 */
public class MoveReservationsToWorkUseCase {

    private final ReservationRepository repository;
    private final HoldCopyForNextReservationUseCase holdCopy;
    private final PublishReservationEventPort eventPort;
    private final Clock clock;

    public MoveReservationsToWorkUseCase(ReservationRepository repository, HoldCopyForNextReservationUseCase holdCopy,
                                         PublishReservationEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.holdCopy = holdCopy;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** @return số đặt trước đã chuyển sang tác phẩm mới (không tính cái bị hủy do trùng). */
    @Transactional
    public int execute(UUID editionId, UUID newWorkId) {
        List<Reservation> toMove = repository.findActiveByTitle(editionId).stream()
                .filter(r -> !newWorkId.equals(r.workId())).toList();
        if (toMove.isEmpty()) {
            return 0;
        }
        Map<UUID, Reservation> existingByReader = new HashMap<>();
        for (Reservation e : repository.findActiveInWorkByReaders(newWorkId,
                toMove.stream().map(Reservation::readerId).distinct().toList())) {
            existingByReader.put(e.readerId(), e);
        }
        int moved = 0;
        for (Reservation r : toMove) {
            Reservation existing = existingByReader.get(r.readerId());
            if (existing == null) {
                r.moveToWork(newWorkId);
                repository.save(r);
                existingByReader.put(r.readerId(), r);
                moved++;
            } else if (keepMoved(r, existing)) {
                discard(existing);
                r.moveToWork(newWorkId);
                repository.save(r);
                existingByReader.put(r.readerId(), r);
                moved++;
            } else {
                r.moveToWork(newWorkId);
                discard(r);
            }
        }
        return moved;
    }

    private static boolean keepMoved(Reservation moved, Reservation existing) {
        boolean movedReady = moved.status() == ReservationStatus.READY;
        boolean existingReady = existing.status() == ReservationStatus.READY;
        if (movedReady != existingReady) return movedReady;
        return moved.reservedAt().isBefore(existing.reservedAt());
    }

    /** Hủy đặt trước trùng; nếu đang giữ bản sao thì giữ cho người kế tiếp hoặc trả về kho. */
    private void discard(Reservation r) {
        boolean holding = r.status() == ReservationStatus.READY;
        UUID copyId = r.copyId();
        r.cancel(Instant.now(clock));
        repository.save(r);
        r.pullEvents().forEach(eventPort::publish);
        if (holding) {
            holdCopy.holdOrRelease(r.workId(), r.language(), r.titleId(), copyId);
        }
    }
}
