package lms.api.circulation.client;

import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReservationView;
import java.util.List;
import java.util.UUID;

/** Cổng sang circulation-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface CirculationClient {

    BorrowingView borrow(BorrowBookRequest request);

    BorrowingView returnBook(UUID borrowingId, UUID bookCopyId);

    BorrowingView renew(UUID borrowingId);

    BorrowingView getBorrowing(UUID borrowingId);

    ReservationView reserve(UUID titleId);

    List<ReservationView> myReservations();

    ReservationView cancelReservation(UUID reservationId);

    ExpirationResult expireReservations();
}
