package lms.catalog.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Phân loại chuẩn (publisherId, categoryId, authorIds) là tùy chọn. */
public record CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice, UUID publisherId,
                                 UUID categoryId, List<UUID> authorIds) {

    public CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice) {
        this(name, author, isbn, coverPrice, null, null, null);
    }
}
