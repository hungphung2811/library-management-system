package lms.sales.stock.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.Money;

/**
 * Hình chiếu chỉ đọc của mặt hàng đang bán cho storefront (ADR-0019 2.2). {@code workId} là snapshot id tác phẩm (ADR-0026, có thể null). {@code coverPriceSnapshot} chỉ là khóa sắp
 * (không hiển thị, không tính tiền); {@code inStock = available > 0}; {@code listedAt} = ngày đưa vào bán.
 */
public record StorefrontRow(UUID titleId, Money sellingPrice, Long coverPriceSnapshot, boolean inStock,
                            Instant listedAt, UUID workId) {
    /** Không biết tác phẩm (snapshot {@code work_id} trống). */
    public StorefrontRow(UUID titleId, Money sellingPrice, Long coverPriceSnapshot, boolean inStock, Instant listedAt) {
        this(titleId, sellingPrice, coverPriceSnapshot, inStock, listedAt, null);
    }
}
