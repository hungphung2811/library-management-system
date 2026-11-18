package lms.customer.reader.client.http;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.customer.reader.client.CirculationReaderClient;
import lms.customer.reader.dto.ReaderDtos.BorrowingItemView;
import lms.customer.reader.dto.ReaderDtos.BorrowingView;
import lms.customer.reader.dto.ReaderDtos.FineView;
import lms.customer.reader.dto.ReaderDtos.FinesView;
import lms.customer.web.PublicApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * {@code /internal/borrowings/mine|{id}}, {@code /internal/fines/mine}, {@code /internal/reservations[/{id}]} của
 * circulation-service. Phản hồi được đọc vào các record {@code Upstream*} chỉ chứa trường cần dùng rồi ÁNH XẠ TỪNG TRƯỜNG
 * sang DTO công khai, nên userId, readerId, tenantId, copyId, bookCopyId (và trường nội bộ thêm sau này) không thể lọt ra.
 */
public class HttpCirculationReaderClient implements CirculationReaderClient {

    private final RestClient client;

    public HttpCirculationReaderClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<BorrowingView> myBorrowings(String userToken) {
        List<UpstreamBorrowing> body = client.get().uri("/internal/borrowings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve()
                .body(new ParameterizedTypeReference<List<UpstreamBorrowing>>() { });
        if (body == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return body.stream().map(HttpCirculationReaderClient::view).toList();
    }

    @Override
    public BorrowingView myBorrowing(String userToken, UUID borrowingId) {
        return view(client.get().uri("/internal/borrowings/{id}", borrowingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamBorrowing.class));
    }

    @Override
    public FinesView myFines(String userToken) {
        UpstreamFines body = client.get().uri("/internal/fines/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamFines.class);
        if (body == null) {
            throw PublicApiException.serviceUnavailable();
        }
        List<UpstreamFine> fines = body.fines() == null ? List.of() : body.fines();
        return new FinesView(body.totalUnpaid() == null ? BigDecimal.ZERO : body.totalUnpaid(),
                fines.stream().map(f -> new FineView(f.id(), f.borrowingId(), f.reason(), f.amount(), f.status(),
                        f.createdAt(), f.paidAt())).toList());
    }

    @Override
    public List<ReservationRow> myReservations(String userToken) {
        List<UpstreamReservation> body = client.get().uri("/internal/reservations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve()
                .body(new ParameterizedTypeReference<List<UpstreamReservation>>() { });
        if (body == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return body.stream().map(HttpCirculationReaderClient::row).toList();
    }

    @Override
    public ReservationRow cancelReservation(String userToken, UUID reservationId) {
        return row(client.delete().uri("/internal/reservations/{id}", reservationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamReservation.class));
    }

    private static BorrowingView view(UpstreamBorrowing b) {
        if (b == null || b.id() == null) {
            throw PublicApiException.serviceUnavailable();
        }
        List<UpstreamItem> items = b.items() == null ? List.of() : b.items();
        return new BorrowingView(b.id(), b.status(), b.borrowDate(), b.dueDate(), b.renewCount(),
                items.stream().map(i -> new BorrowingItemView(i.returnDate(), i.condition())).toList());
    }

    private static ReservationRow row(UpstreamReservation r) {
        if (r == null || r.id() == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return new ReservationRow(r.id(), r.titleId(), r.status(), r.queuePosition(), r.reservedAt(), r.holdUntil());
    }

    // ---------- Phản hồi của circulation (chỉ đọc các trường này) ----------

    record UpstreamItem(LocalDate returnDate, String condition) {
    }

    record UpstreamBorrowing(UUID id, String status, LocalDate borrowDate, LocalDate dueDate, int renewCount,
                             List<UpstreamItem> items) {
    }

    record UpstreamFine(UUID id, UUID borrowingId, String reason, BigDecimal amount, String status, Instant createdAt,
                        Instant paidAt) {
    }

    record UpstreamFines(BigDecimal totalUnpaid, List<UpstreamFine> fines) {
    }

    record UpstreamReservation(UUID id, UUID titleId, String status, long queuePosition, Instant reservedAt,
                               LocalDate holdUntil) {
    }
}
