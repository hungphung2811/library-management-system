package lms.api.circulation.client.http;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReservationView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Gọi API nội bộ /internal/borrowings và /internal/reservations của circulation-service, ánh xạ sang DTO của FE. */
public class HttpCirculationClient implements CirculationClient {

    /** DTO nội bộ của circulation-service; tách khỏi DTO cho FE để hai bên đổi độc lập. */
    record InternalBorrowing(UUID id, UUID userId, String status, LocalDate borrowDate, LocalDate dueDate,
                             int renewCount, List<InternalItem> items) {
    }

    record InternalItem(UUID bookCopyId, LocalDate returnDate) {
    }

    record InternalReservation(UUID id, UUID titleId, String status, long queuePosition, Instant reservedAt,
                               UUID copyId, LocalDate holdUntil) {
    }

    private final RestClient client;

    public HttpCirculationClient(RestClient client) {
        this.client = client;
    }

    private static BorrowingView view(InternalBorrowing b) {
        List<BorrowingView.Item> items = b.items() == null ? List.of()
                : b.items().stream().map(i -> new BorrowingView.Item(i.bookCopyId(), i.returnDate())).toList();
        return new BorrowingView(b.id(), b.userId(), b.status(), b.borrowDate(), b.dueDate(), b.renewCount(), items);
    }

    private static ReservationView view(InternalReservation r) {
        return new ReservationView(r.id(), r.titleId(), r.status(), r.queuePosition(), r.reservedAt(), r.copyId(),
                r.holdUntil());
    }

    @Override
    public BorrowingView borrow(BorrowBookRequest request) {
        return view(client.post().uri("/internal/borrowings").body(request).retrieve().body(InternalBorrowing.class));
    }

    @Override
    public BorrowingView returnBook(UUID borrowingId, UUID bookCopyId) {
        return view(client.post().uri("/internal/borrowings/{id}/returns", borrowingId)
                .body(Map.of("bookCopyId", bookCopyId)).retrieve().body(InternalBorrowing.class));
    }

    @Override
    public BorrowingView renew(UUID borrowingId) {
        return view(client.post().uri("/internal/borrowings/{id}/renewals", borrowingId).retrieve()
                .body(InternalBorrowing.class));
    }

    @Override
    public BorrowingView getBorrowing(UUID borrowingId) {
        return view(client.get().uri("/internal/borrowings/{id}", borrowingId).retrieve()
                .body(InternalBorrowing.class));
    }

    @Override
    public ReservationView reserve(UUID titleId) {
        return view(client.post().uri("/internal/reservations").body(Map.of("titleId", titleId)).retrieve()
                .body(InternalReservation.class));
    }

    @Override
    public List<ReservationView> myReservations() {
        List<InternalReservation> list = client.get().uri("/internal/reservations").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return list == null ? List.of() : list.stream().map(HttpCirculationClient::view).toList();
    }

    @Override
    public ReservationView cancelReservation(UUID reservationId) {
        return view(client.delete().uri("/internal/reservations/{id}", reservationId).retrieve()
                .body(InternalReservation.class));
    }

    @Override
    public ExpirationResult expireReservations() {
        Map<String, Integer> result = client.post().uri("/internal/reservations/expirations").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return new ExpirationResult(result == null ? 0 : result.getOrDefault("expired", 0));
    }
}
