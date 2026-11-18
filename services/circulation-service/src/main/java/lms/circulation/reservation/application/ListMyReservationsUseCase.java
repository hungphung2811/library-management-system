package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ListMyReservationsUseCase {

    private final ReservationRepository repository;

    public ListMyReservationsUseCase(ReservationRepository repository) {
        this.repository = repository;
    }

    public List<ReservationResponse> execute(UUID readerId) {
        List<Reservation> mine = repository.findByReader(readerId);
        // Một truy vấn cho cả danh sách thay vì đếm hàng đợi từng đặt trước (N+1).
        Map<UUID, Long> positions = repository.queuePositions(mine);
        return mine.stream().map(r -> ReservationResponse.from(r, positions.getOrDefault(r.id(), 0L))).toList();
    }
}
