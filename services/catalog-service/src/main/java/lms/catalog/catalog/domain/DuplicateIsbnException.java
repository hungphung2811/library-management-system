package lms.catalog.catalog.domain;

public class DuplicateIsbnException extends RuntimeException {
    public DuplicateIsbnException(String isbn) {
        super("ISBN đã tồn tại: " + isbn);
    }
}
