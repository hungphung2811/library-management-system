package lms.sales.invoicing.application;

import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/** Lỗi dùng lại trong use case hóa đơn (cùng một thân cho "không tồn tại" và "không phải của bạn"). */
final class UseCaseErrors {

    private UseCaseErrors() {
    }

    static SalesException orderNotFound() {
        return new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng");
    }
}
