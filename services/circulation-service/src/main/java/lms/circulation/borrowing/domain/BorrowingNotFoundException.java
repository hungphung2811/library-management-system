package lms.circulation.borrowing.domain;

import java.util.UUID;

public class BorrowingNotFoundException extends RuntimeException {
    public BorrowingNotFoundException(UUID id) {
        super("Không tìm thấy phiếu mượn: " + id);
    }
}
