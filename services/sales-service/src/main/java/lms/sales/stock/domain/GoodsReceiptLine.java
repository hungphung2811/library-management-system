package lms.sales.stock.domain;

import java.util.UUID;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

public record GoodsReceiptLine(UUID titleId, int quantity, Money unitCost) {

    public GoodsReceiptLine {
        if (titleId == null) {
            throw SalesException.invalidRequest("Thiếu đầu sách");
        }
        if (quantity <= 0) {
            throw SalesException.invalidQuantity("Số lượng nhập phải lớn hơn 0");
        }
        if (unitCost == null) {
            throw SalesException.invalidPrice("Thiếu giá nhập");
        }
    }
}
