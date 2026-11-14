package lms.circulation.reservation.application;

import lms.circulation.reservation.domain.ReservationRepository;
import java.util.List;
import java.util.UUID;

public class ListMyReservationsUseCase {

    private final ReservationRepository repository;

    public ListMyReservationsUseCase(ReservationRepository repository) {
        this.repository = repository;
    }

    public List<ReservationResponse> execute(UUID readerId) {
        return repository.findByReader(readerId).stream()
                .map(r -> ReservationResponse.from(r, repository.queuePosition(r)))
                .toList();
    }
}
