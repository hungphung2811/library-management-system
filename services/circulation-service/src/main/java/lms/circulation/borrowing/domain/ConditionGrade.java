package lms.circulation.borrowing.domain;

/** Tình trạng vật lý của bản sao lúc mượn (khớp ConditionGrade của catalog): mới, tốt, trung bình, cũ. */
public enum ConditionGrade {
    NEW, GOOD, FAIR, WORN;

    /** Chuyển tên do catalog trả về; giá trị lạ hoặc rỗng coi như chưa ghi nhận (null), không làm hỏng lượt mượn. */
    public static ConditionGrade fromNameOrNull(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
