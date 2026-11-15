package lms.circulation.borrowing.domain;

/**
 * Tình trạng bản sao khi đóng lượt mượn (UC07 nguyên vẹn/hỏng, UC08 mất). Lưu ở cột borrowing_items.item_condition
 * (CHECK của migration V4: GOOD, DAMAGED, LOST).
 */
public enum ItemCondition {
    /** Trả nguyên vẹn. */
    GOOD,
    /** Trả nhưng hỏng. */
    DAMAGED,
    /** Mất. */
    LOST
}
