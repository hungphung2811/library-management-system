package lms.sales.invoicing.infrastructure.web;

import jakarta.validation.constraints.Size;
import lms.sales.invoicing.application.BuyerInput;

/**
 * Thông tin người mua trong thân request (dữ liệu cá nhân: {@code toString} không in). MST sai định dạng thì 400
 * INVALID_TAX_CODE; có MST thì bắt buộc đơn vị và địa chỉ (kiểm ở domain).
 */
public record BuyerRequest(@Size(max = 255) String name, @Size(max = 255) String company,
                           @Size(max = 14) String taxCode, @Size(max = 500) String address,
                           @Size(max = 255) String email) {

    BuyerInput toInput() {
        return new BuyerInput(name, company, taxCode, address, email);
    }

    @Override
    public String toString() {
        return "BuyerRequest[***]";
    }
}
