package lms.support.audit.domain;

public class InvalidAuditQueryException extends RuntimeException {
    public InvalidAuditQueryException(String message) {
        super(message);
    }
}
