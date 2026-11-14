package lms.circulation.borrowing.domain;

public class BorrowingLimitExceededException extends RuntimeException {
    public BorrowingLimitExceededException(int limit) {
        super("Vượt giới hạn mượn: tối đa " + limit + " bản sao đang mượn cùng lúc");
    }
}
