package lms.circulation.borrowing.domain;

public class InvalidBorrowingException extends RuntimeException {
    public InvalidBorrowingException(String message) {
        super(message);
    }
}
