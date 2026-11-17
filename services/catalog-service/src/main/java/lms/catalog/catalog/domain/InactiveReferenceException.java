package lms.catalog.catalog.domain;

import java.util.UUID;

/** Gán một mục đã ngừng dùng vào đầu sách/bản sao. */
public class InactiveReferenceException extends RuntimeException {

    private final ReferenceKind kind;

    public InactiveReferenceException(ReferenceKind kind, UUID id) {
        super("Không thể dùng " + kind.label() + " đã ngừng sử dụng (" + id + ")");
        this.kind = kind;
    }

    public ReferenceKind kind() { return kind; }
}
