package lms.sales.invoicing.application;

import lms.sales.invoicing.domain.BuyerInfo;

/** Thông tin người mua do người dùng nhập (chưa chuẩn hóa). Dữ liệu cá nhân: {@code toString} không in. */
public record BuyerInput(String name, String company, String taxCode, String address, String email) {

    /** Chuẩn hóa và kiểm (MST đúng định dạng, có MST thì cần đơn vị và địa chỉ). */
    public BuyerInfo toBuyer() {
        return BuyerInfo.of(name, company, taxCode, address, email);
    }

    @Override
    public String toString() {
        return "BuyerInput[***]";
    }
}
