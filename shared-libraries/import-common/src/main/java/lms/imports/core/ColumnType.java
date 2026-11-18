package lms.imports.core;

/** Kiểu của cột; thư viện chỉ kiểm cú pháp chung (số, ngày, enum...), quy tắc nghiệp vụ do handler kiểm bằng domain. */
public enum ColumnType {
    TEXT,
    INTEGER,
    DECIMAL,
    MONEY,
    DATE,
    BOOLEAN,
    ENUM,
    /** Nhiều giá trị trong một ô, phân tách bằng {@code ;}. */
    LIST
}
