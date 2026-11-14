package lms.database.api.exception;

public abstract class DatabaseException extends RuntimeException {
    protected DatabaseException(String message) {
        super(message);
    }

    protected DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
