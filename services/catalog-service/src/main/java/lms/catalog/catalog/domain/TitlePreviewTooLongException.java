package lms.catalog.catalog.domain;

/** Mô tả hoặc đoạn đọc thử vượt giới hạn (đếm theo code point). Ánh xạ 400 TITLE_PREVIEW_TOO_LONG. */
public class TitlePreviewTooLongException extends RuntimeException {
    public TitlePreviewTooLongException(String field, int max) {
        super(field + " tối đa " + max + " ký tự");
    }
}
