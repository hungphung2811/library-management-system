package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import java.math.BigDecimal;
import java.util.UUID;

public record TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                            long availableCopies, UUID tenantId) {

    public static TitleResponse from(Title title, long availableCopies) {
        return new TitleResponse(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                availableCopies, title.tenantId());
    }
}
