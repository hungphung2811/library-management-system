package lms.circulation.fine.domain;

public class InvalidFineException extends RuntimeException {
    public InvalidFineException(String message) {
        super(message);
    }
}
