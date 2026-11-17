package lms.catalog.catalog.domain;

import java.util.UUID;

public class ReferenceNotFoundException extends RuntimeException {

    private final ReferenceKind kind;

    public ReferenceNotFoundException(ReferenceKind kind, UUID id) {
        super("Không tìm thấy " + kind.label() + " " + id);
        this.kind = kind;
    }

    public ReferenceKind kind() { return kind; }
}
