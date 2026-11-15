package lms.circulation.borrowing.domain;

/** Loại độc giả (đồng bộ với identity-service). Hạn mức mượn theo loại là {@link BorrowRule} trong CSDL (QĐ1). */
public enum ReaderType {
    STUDENT, LECTURER
}
