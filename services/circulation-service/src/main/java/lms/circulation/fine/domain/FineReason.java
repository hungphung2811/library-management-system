package lms.circulation.fine.domain;

public enum FineReason {
    /** QĐ3: trả trễ hạn. */
    LATE_RETURN,
    /** QĐ4: bồi thường mất sách. */
    LOST,
    /** QĐ4: sách trả về bị hỏng. */
    DAMAGED
}
