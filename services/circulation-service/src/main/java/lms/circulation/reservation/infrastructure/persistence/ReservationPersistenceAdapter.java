package lms.circulation.reservation.infrastructure.persistence;

import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class ReservationPersistenceAdapter implements ReservationRepository {

    private static final Set<ReservationStatus> ACTIVE = Set.of(ReservationStatus.WAITING, ReservationStatus.READY);

    private final SpringDataReservationRepository jpa;

    ReservationPersistenceAdapter(SpringDataReservationRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Reservation save(Reservation r) {
        try {
            jpa.saveAndFlush(new ReservationJpaEntity(r.id(), r.readerId(), r.titleId(), r.reservedAt(), r.status(),
                    r.copyId(), r.holdUntil()));
        } catch (DataIntegrityViolationException e) {
            // unique index uq_reservations_active: hai yêu cầu đặt trước đồng thời cho cùng độc giả và đầu sách
            throw new AlreadyReservedException();
        }
        return r;
    }

    @Override
    public Optional<Reservation> findById(UUID id) {
        return jpa.findById(id).map(ReservationPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsActive(UUID readerId, UUID titleId) {
        return jpa.existsByReaderIdAndTitleIdAndStatusIn(readerId, titleId, ACTIVE);
    }

    @Override
    public List<Reservation> findByReader(UUID readerId) {
        return jpa.findByReaderIdOrderByReservedAtDesc(readerId).stream()
                .map(ReservationPersistenceAdapter::toDomain).toList();
    }

    @Override
    public long queuePosition(Reservation r) {
        if (r.status() != ReservationStatus.WAITING) return 0;
        return jpa.countAheadOrEqual(r.titleId(), ReservationStatus.WAITING, r.reservedAt(), r.id());
    }

    @Override
    public Optional<Reservation> findNextWaitingForUpdate(UUID titleId) {
        return jpa.findFirstByTitleIdAndStatusOrderByReservedAtAscIdAsc(titleId, ReservationStatus.WAITING)
                .map(ReservationPersistenceAdapter::toDomain);
    }

    @Override
    public List<Reservation> findHoldExpired(LocalDate today) {
        return jpa.findByStatusAndHoldUntilBefore(ReservationStatus.READY, today).stream()
                .map(ReservationPersistenceAdapter::toDomain).toList();
    }

    @Override
    public boolean existsReadyForCopy(UUID copyId) {
        return jpa.existsByCopyIdAndStatus(copyId, ReservationStatus.READY);
    }

    private static Reservation toDomain(ReservationJpaEntity e) {
        return Reservation.restore(e.getId(), e.getReaderId(), e.getTitleId(), e.getReservedAt(), e.getStatus(),
                e.getCopyId(), e.getHoldUntil());
    }
}
