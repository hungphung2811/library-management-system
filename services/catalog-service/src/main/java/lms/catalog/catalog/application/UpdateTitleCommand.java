package lms.catalog.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** PUT thay toàn bộ: publisherId/categoryId/authorIds null nghĩa là gỡ phân loại chuẩn tương ứng. */
public record UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                                 UUID publisherId, UUID categoryId, List<UUID> authorIds) {

    public UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        this(id, name, author, isbn, coverPrice, null, null, null);
    }
}
