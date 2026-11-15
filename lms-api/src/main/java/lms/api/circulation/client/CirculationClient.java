package lms.api.circulation.client;

import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.CollectFinesResult;
import lms.api.circulation.dto.ExpirationResult;
import lms.api.circulation.dto.ReaderFinesView;
import lms.api.circulation.dto.ReportLostRequest;
import lms.api.circulation.dto.ReservationView;
import lms.api.circulation.dto.ReturnCopyRequest;
import java.util.List;
import java.util.UUID;

/** Cổng sang circulation-service (API nội bộ). Lỗi 4xx của service được ném dưới dạng RestClientResponseException. */
public interface CirculationClient {

    BorrowingView borrow(BorrowBookRequest request);

    BorrowingView returnBook(UUID borrowingId, UUID bookCopyId);

    BorrowingView renew(UUID borrowingId);

    BorrowingView getBorrowing(UUID borrowingId);

    BorrowingView returnCopy(ReturnCopyRequest request);

    BorrowingView reportLost(ReportLostRequest request);

    List<BorrowingView> myBorrowings();

    List<BorrowingView> borrowingsOfReader(UUID readerId);

    ReaderFinesView myFines();

    ReaderFinesView finesOfReader(UUID readerId);

    CollectFinesResult collectFines(UUID readerId);

    ReservationView reserve(UUID titleId);

    List<ReservationView> myReservations();

    ReservationView cancelReservation(UUID reservationId);

    ExpirationResult expireReservations();
}
