package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleExtras;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Phân loại chuẩn (publisherId, categoryId, authorIds) là tùy chọn. */
public record CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice, UUID publisherId,
                                 UUID categoryId, List<UUID> authorIds, TitleExtras extras, UUID workId,
                                 Integer partNumber, String partLabel) {

    public CreateTitleCommand {
        extras = extras == null ? TitleExtras.EMPTY : extras;
    }

    /** Dạng 8 trường (không workId: tự tạo tác phẩm 1:1; không nhãn phần). */
    public CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice, UUID publisherId,
                              UUID categoryId, List<UUID> authorIds, TitleExtras extras) {
        this(name, author, isbn, coverPrice, publisherId, categoryId, authorIds, extras, null, null, null);
    }

    public CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice, UUID publisherId,
                              UUID categoryId, List<UUID> authorIds) {
        this(name, author, isbn, coverPrice, publisherId, categoryId, authorIds, null);
    }

    public CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice) {
        this(name, author, isbn, coverPrice, null, null, null);
    }
}
