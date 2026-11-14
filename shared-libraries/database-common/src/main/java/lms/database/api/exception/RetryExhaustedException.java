package lms.database.api.exception;

public class RetryExhaustedException extends DatabaseException {

    private final int attempts;

    public RetryExhaustedException(String description, int attempts, Throwable cause) {
        super(description + " thất bại sau " + attempts + " lần thử: " + cause.getMessage(), cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
