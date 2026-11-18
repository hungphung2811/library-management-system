package lms.circulation.fine.domain;

import java.math.BigDecimal;

/** Giá gốc đã chọn: nguồn thực dùng và cờ fallback (đã rơi về giá bìa vì bản sao không có giá mua). */
public record PriceQuote(BigDecimal basePrice, PriceSourceKind source, boolean fallback) {
}
