package lms.sales.ordering.domain;

import java.util.UUID;
import lms.sales.shared.domain.Money;

/** Dòng phiếu trả: số lượng trả của một dòng đơn, cách xử lý hàng và số tiền hoàn của dòng. */
public record ReturnLine(UUID id, UUID orderLineId, UUID titleId, int quantity, Disposition disposition,
                         Money refundAmount) {
}
