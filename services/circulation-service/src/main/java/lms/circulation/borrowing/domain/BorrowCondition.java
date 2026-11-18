package lms.circulation.borrowing.domain;

/**
 * Tình trạng bản sao được chụp lúc lập phiếu mượn (bằng chứng đối chiếu khi trả), bất biến sau khi lập phiếu.
 * grade null nghĩa là bản sao chưa có tình trạng ở catalog ("chưa ghi nhận"); cả đối tượng vắng ở {@link BorrowingItem}
 * nghĩa là lượt mượn lập trước khi có tính năng ("không có dữ liệu lúc mượn").
 */
public record BorrowCondition(ConditionGrade grade, String note) {

    public static final int MAX_NOTE_LENGTH = 500;

    public BorrowCondition {
        if (note != null) {
            note = note.strip();
            if (note.isEmpty()) {
                note = null;
            } else if (note.length() > MAX_NOTE_LENGTH) {
                throw new InvalidBorrowingException("Ghi chú tình trạng tối đa " + MAX_NOTE_LENGTH + " ký tự");
            }
        }
    }

    /** Ghi đè từng phần: trường của override khác null thay trường tương ứng (thủ thư sửa khi thực tế khác bản sao). */
    public BorrowCondition overriddenBy(ConditionGrade grade, String note) {
        return new BorrowCondition(grade != null ? grade : this.grade, note != null ? note : this.note);
    }
}
