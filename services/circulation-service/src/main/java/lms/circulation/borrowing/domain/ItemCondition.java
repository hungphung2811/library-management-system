package lms.circulation.borrowing.domain;

/** Tình trạng bản sao khi đóng lượt mượn (cột borrowing_items.item_condition, V4). */
public enum ItemCondition {
    /** Trả nguyên vẹn. */
    GOOD,
    /** Trả nhưng hỏng. */
    DAMAGED,
    /** Mất. */
    LOST
}
