package lms.sales.invoicing.domain;

/** Thông tin người bán (snapshot lên hóa đơn lúc phát hành). Điện thoại và email tùy chọn. */
public record SellerInfo(String legalName, String taxCode, String address, String phone, String email) {

    public SellerInfo {
        if (legalName == null || legalName.isBlank() || taxCode == null || taxCode.isBlank() || address == null
                || address.isBlank()) {
            throw new IllegalArgumentException("Người bán thiếu tên pháp lý, mã số thuế hoặc địa chỉ");
        }
    }
}
