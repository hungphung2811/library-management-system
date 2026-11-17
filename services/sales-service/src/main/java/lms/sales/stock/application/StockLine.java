package lms.sales.stock.application;

import java.util.UUID;
import lms.sales.shared.domain.SalesException;

public record StockLine(UUID titleId, int quantity) {

    public StockLine {
        if (titleId == null) {
            throw SalesException.invalidRequest("Thiếu đầu sách");
        }
        if (quantity <= 0) {
            throw SalesException.invalidQuantity("Số lượng phải lớn hơn 0");
        }
    }
}
