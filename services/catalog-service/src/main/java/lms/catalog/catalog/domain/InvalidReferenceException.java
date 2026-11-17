package lms.catalog.catalog.domain;

public class InvalidReferenceException extends RuntimeException {

    private final ReferenceKind kind;

    public InvalidReferenceException(ReferenceKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ReferenceKind kind() { return kind; }
}
