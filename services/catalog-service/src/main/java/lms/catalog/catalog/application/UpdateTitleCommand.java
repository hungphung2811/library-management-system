package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleExtras;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** PUT thay toàn bộ: extras null nghĩa là xóa thông tin bổ sung; publisherId/categoryId/authorIds null nghĩa là gỡ phân loại chuẩn tương ứng. */
public record UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                                 UUID publisherId, UUID categoryId, List<UUID> authorIds, TitleExtras extras) {

    public UpdateTitleCommand {
        extras = extras == null ? TitleExtras.EMPTY : extras;
    }

    public UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                              UUID publisherId, UUID categoryId, List<UUID> authorIds) {
        this(id, name, author, isbn, coverPrice, publisherId, categoryId, authorIds, null);
    }

    public UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        this(id, name, author, isbn, coverPrice, null, null, null);
    }
}
