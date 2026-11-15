package lms.api.circulation.client.http;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.CollectFinesResult;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.FineView;
import lms.api.circulation.dto.ReaderFinesView;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReservationView;
import lms.api.circulation.dto.ReturnCopyRequest;
import java.math.BigDecimal;
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
                             int renewCount, List<InternalItem> items, List<InternalFine> fines) {
    }

    record InternalItem(UUID bookCopyId, LocalDate returnDate, String condition) {
    }

    record InternalFine(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, String reason,
                        BigDecimal amount, String status, Instant createdAt, Instant paidAt) {
    }

    record InternalReaderFines(UUID readerId, BigDecimal totalUnpaid, List<InternalFine> fines) {
    }

    record InternalCollectFines(UUID readerId, int collectedCount, BigDecimal totalCollected,
                                List<InternalFine> fines) {
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
                : b.items().stream()
                .map(i -> new BorrowingView.Item(i.bookCopyId(), i.returnDate(), i.condition())).toList();
        return new BorrowingView(b.id(), b.userId(), b.status(), b.borrowDate(), b.dueDate(), b.renewCount(), items,
                fines(b.fines()));
    }

    private static FineView view(InternalFine f) {
        return new FineView(f.id(), f.readerId(), f.borrowingId(), f.bookCopyId(), f.reason(), f.amount(),
                f.status(), f.createdAt(), f.paidAt());
    }

    private static List<FineView> fines(List<InternalFine> list) {
        return list == null ? List.of() : list.stream().map(HttpCirculationClient::view).toList();
    }

    private static List<BorrowingView> borrowings(List<InternalBorrowing> list) {
        return list == null ? List.of() : list.stream().map(HttpCirculationClient::view).toList();
    }

    private static ReaderFinesView view(InternalReaderFines r) {
        return new ReaderFinesView(r.readerId(), r.totalUnpaid(), fines(r.fines()));
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
    public BorrowingView returnCopy(ReturnCopyRequest request) {
        return view(client.post().uri("/internal/borrowings/returns").body(request).retrieve()
                .body(InternalBorrowing.class));
    }

    @Override
    public BorrowingView reportLost(ReportLostRequest request) {
        return view(client.post().uri("/internal/borrowings/losses").body(request).retrieve()
                .body(InternalBorrowing.class));
    }

    @Override
    public List<BorrowingView> myBorrowings() {
        return borrowings(client.get().uri("/internal/borrowings/mine").retrieve()
                .body(new ParameterizedTypeReference<List<InternalBorrowing>>() { }));
    }

    @Override
    public List<BorrowingView> borrowingsOfReader(UUID readerId) {
        return borrowings(client.get().uri("/internal/borrowings?readerId={id}", readerId).retrieve()
                .body(new ParameterizedTypeReference<List<InternalBorrowing>>() { }));
    }

    @Override
    public ReaderFinesView myFines() {
        return view(client.get().uri("/internal/fines/mine").retrieve().body(InternalReaderFines.class));
    }

    @Override
    public ReaderFinesView finesOfReader(UUID readerId) {
        return view(client.get().uri("/internal/fines?readerId={id}", readerId).retrieve()
                .body(InternalReaderFines.class));
    }

    @Override
    public CollectFinesResult collectFines(UUID readerId) {
        InternalCollectFines r = client.post().uri("/internal/fines/payments").body(Map.of("readerId", readerId))
                .retrieve().body(InternalCollectFines.class);
        return new CollectFinesResult(r.readerId(), r.collectedCount(), r.totalCollected(), fines(r.fines()));
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
