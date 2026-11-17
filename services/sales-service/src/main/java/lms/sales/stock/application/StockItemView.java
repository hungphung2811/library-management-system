package lms.sales.stock.application;

import lms.sales.shared.domain.Money;
import lms.sales.stock.domain.StockItem;

/** Mặt hàng kèm thông tin tra từ catalog. titleName/effectivePrice là null nếu catalog không có đầu sách. */
public record StockItemView(StockItem item, String titleName, Money effectivePrice, boolean lowStock) {
}
