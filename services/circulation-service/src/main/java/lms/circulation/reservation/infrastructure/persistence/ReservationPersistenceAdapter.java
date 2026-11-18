package lms.circulation.reservation.infrastructure.persistence;

import lms.circulation.reservation.domain.AlreadyReservedException;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        return jpa.findInTenantById(id).map(ReservationPersistenceAdapter::toDomain);
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
    public Map<UUID, Long> queuePositions(Collection<Reservation> reservations) {
        Map<UUID, Long> result = new HashMap<>();
        Set<UUID> titleIds = new HashSet<>();
        for (Reservation r : reservations) {
            result.put(r.id(), 0L);
            if (r.status() == ReservationStatus.WAITING) titleIds.add(r.titleId());
        }
        if (titleIds.isEmpty()) return result;
        // Xếp hạng trong hàng đợi WAITING của từng đầu sách theo đúng thứ tự (reservedAt, id) của queuePosition.
        Map<UUID, Long> rankByTitle = new HashMap<>();
        for (Object[] row : jpa.findQueueOrder(titleIds, ReservationStatus.WAITING)) {
            long rank = rankByTitle.merge((UUID) row[0], 1L, Long::sum);
            if (result.containsKey((UUID) row[1])) result.put((UUID) row[1], rank);
        }
        return result;
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

    @Override
    public Optional<Reservation> findReadyByCopyId(UUID copyId) {
        return jpa.findFirstByCopyIdAndStatus(copyId, ReservationStatus.READY).map(ReservationPersistenceAdapter::toDomain);
    }

    private static Reservation toDomain(ReservationJpaEntity e) {
        return Reservation.restore(e.getId(), e.getReaderId(), e.getTitleId(), e.getReservedAt(), e.getStatus(),
                e.getCopyId(), e.getHoldUntil());
    }
}
