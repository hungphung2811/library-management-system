package lms.circulation.borrowing.domain;

public class BorrowingLimitExceededException extends RuntimeException {
    public BorrowingLimitExceededException(int limit, long remaining) {
        super("Vượt hạn mức mượn: tối đa " + limit + " cuốn đang mượn cùng lúc, còn được mượn " + Math.max(0, remaining) + " cuốn");
    }
}
