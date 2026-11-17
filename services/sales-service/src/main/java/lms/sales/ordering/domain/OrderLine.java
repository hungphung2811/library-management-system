package lms.sales.ordering.domain;

import java.util.UUID;
import lms.sales.shared.domain.Money;

/** Dòng đơn kèm snapshot tên/ISBN/đơn giá lúc bán và kết quả tính tiền của dòng. */
public record OrderLine(UUID id, int lineNo, UUID titleId, String titleName, String isbn, int quantity,
                        Money unitPrice, Money lineDiscount, Money allocatedOrderDiscount, Money amount,
                        Money vatAmount, Money lineTotal) {
}
