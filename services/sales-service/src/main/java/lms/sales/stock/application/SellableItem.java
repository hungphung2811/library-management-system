package lms.sales.stock.application;

import java.util.UUID;
import lms.sales.shared.domain.Money;

/** Thông tin mặt hàng mà ordering cần khi bán (ordering không được dùng stock.domain). sellingPrice null = dùng giá bìa. */
public record SellableItem(UUID titleId, boolean active, Money sellingPrice, long available) {
}
