package lms.api.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Đầu sách trả cho FE; availableCopies là số bản sao sẵn có. */
public record TitleView(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies) {
}
