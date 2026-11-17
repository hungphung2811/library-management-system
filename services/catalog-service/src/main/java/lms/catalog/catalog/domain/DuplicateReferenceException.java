package lms.catalog.catalog.domain;

public class DuplicateReferenceException extends RuntimeException {

    private final ReferenceKind kind;

    public DuplicateReferenceException(ReferenceKind kind, String name) {
        super(kind.nameLabel() + " " + kind.label() + " \"" + name + "\" đã tồn tại");
        this.kind = kind;
    }

    public ReferenceKind kind() { return kind; }
}
