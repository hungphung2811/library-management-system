package lms.circulation.borrowing.domain;

/**
 * Loại độc giả (đồng bộ với identity-service), dùng cho quy tắc mượn (borrow_rules.reader_type). Hạn mức mượn theo
 * loại là {@link BorrowRule} trong CSDL, riêng cho từng tenant (QĐ1).
 */
public enum ReaderType {
    STUDENT,
    LECTURER
}
