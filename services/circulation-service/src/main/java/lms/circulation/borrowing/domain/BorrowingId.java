package lms.circulation.borrowing.domain;

import java.util.UUID;

public record BorrowingId(UUID value) {

    public BorrowingId {
        if (value == null) throw new IllegalArgumentException("BorrowingId không được null");
    }

    public static BorrowingId newId() {
        return new BorrowingId(UUID.randomUUID());
    }
}
