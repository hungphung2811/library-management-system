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
            jpa.saveAndFlush(new ReservationJpaEntity(r.id(), r.readerId(), r.titleId(), r.workId(), r.language(),
                    r.reservedAt(), r.status(), r.copyId(), r.holdUntil()));
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
    public boolean existsActiveInWork(UUID readerId, UUID workId) {
        return jpa.existsByReaderIdAndWorkIdAndStatusIn(readerId, workId, ACTIVE);
    }

    @Override
    public List<Reservation> findActiveByTitle(UUID titleId) {
        return jpa.findByTitleIdAndStatusIn(titleId, ACTIVE).stream().map(ReservationPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Reservation> findActiveInWorkByReaders(UUID workId, Collection<UUID> readerIds) {
        if (readerIds.isEmpty()) return List.of();
        return jpa.findByWorkIdAndReaderIdInAndStatusIn(workId, readerIds, ACTIVE).stream()
                .map(ReservationPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Optional<Reservation> findNextWaitingInWorkForUpdate(UUID workId, String language) {
        // language null (ấn bản chưa khai ngôn ngữ) chỉ khớp đặt trước không khai ngôn ngữ ("" không trùng ngôn ngữ nào)
        String lang = language == null ? "" : language;
        return jpa.findNextInWork(workId, lang, ReservationStatus.WAITING, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().map(ReservationPersistenceAdapter::toDomain);
    }

    @Override
    public List<Reservation> findByReader(UUID readerId) {
        return jpa.findByReaderIdOrderByReservedAtDesc(readerId).stream()
                .map(ReservationPersistenceAdapter::toDomain).toList();
    }

    @Override
    public long queuePosition(Reservation r) {
        if (r.status() != ReservationStatus.WAITING) return 0;
        return jpa.countAheadOrEqualInWork(r.workId(), langKey(r.language()), ReservationStatus.WAITING, r.reservedAt(),
                r.id());
    }

    private static String langKey(String language) {
        return language == null ? "" : language;
    }

    @Override
    public Map<UUID, Long> queuePositions(Collection<Reservation> reservations) {
        Map<UUID, Long> result = new HashMap<>();
        Set<UUID> workIds = new HashSet<>();
        for (Reservation r : reservations) {
            result.put(r.id(), 0L);
            if (r.status() == ReservationStatus.WAITING) workIds.add(r.workId());
        }
        if (workIds.isEmpty()) return result;
        // Hàng đợi WAITING mỗi tác phẩm theo (reservedAt, id); vị trí của r = số đặt trước đứng trước hoặc bằng r mà tranh
        // được cùng bản sao với r (cùng quy tắc countAheadOrEqualInWork/findNextInWork), tính trong bộ nhớ từ một truy vấn.
        Map<UUID, List<Object[]>> queueByWork = new HashMap<>();
        for (Object[] row : jpa.findQueueOrderByWork(workIds, ReservationStatus.WAITING)) {
            queueByWork.computeIfAbsent((UUID) row[0], k -> new java.util.ArrayList<>()).add(row);
        }
        for (Reservation r : reservations) {
            if (r.status() != ReservationStatus.WAITING) continue;
            String lang = langKey(r.language());
            long count = 0;
            boolean found = false;
            for (Object[] row : queueByWork.getOrDefault(r.workId(), List.of())) {
                String other = (String) row[1];
                if (lang.isEmpty() || other.isEmpty() || other.equals(lang)) count++;
                if (r.id().equals(row[2])) {
                    found = true;
                    break;
                }
            }
            if (found) result.put(r.id(), count);
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
        // work_id NULL (dòng jar cũ chèn) thì domain suy tác phẩm 1:1 từ title_id
        return Reservation.restore(e.getId(), e.getReaderId(), e.getTitleId(), e.getWorkId(), e.getLanguage(),
                e.getReservedAt(), e.getStatus(), e.getCopyId(), e.getHoldUntil());
    }
}
