package lms.catalog.inventory.domain;

public class InvalidCopyException extends RuntimeException {
    public InvalidCopyException(String message) {
        super(message);
    }
}
