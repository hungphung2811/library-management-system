package lms.customer.reader.client;

import java.util.List;
import java.util.UUID;
import lms.customer.reader.dto.ReaderDtos.BorrowingView;
import lms.customer.reader.dto.ReaderDtos.FinesView;

/**
 * Cổng sang circulation-service cho độc giả. Mọi lời gọi chuyển tiếp TOKEN ĐỘC GIẢ (không phải token dịch vụ): circulation
 * tự lấy userId từ {@code sub}, kiểm module LENDING, tenant (RLS) và sở hữu. Lỗi 4xx của circulation được ném nguyên để
 * PublicExceptionHandler lọc theo danh sách cho phép.
 */
public interface CirculationReaderClient {

    List<BorrowingView> myBorrowings(String userToken);

    BorrowingView myBorrowing(String userToken, UUID borrowingId);

    FinesView myFines(String userToken);

    /** Đặt trước của tôi, CHƯA có tên sách (xem {@link ReservationRow}); tên do controller ghép từ catalog theo lô. */
    List<ReservationRow> myReservations(String userToken);

    ReservationRow cancelReservation(String userToken, UUID reservationId);

    /** Đặt trước nội bộ đã bỏ copyId; title chưa có. */
    record ReservationRow(UUID id, UUID titleId, String status, long queuePosition, java.time.Instant reservedAt,
                          java.time.LocalDate holdUntil) {
    }
}
