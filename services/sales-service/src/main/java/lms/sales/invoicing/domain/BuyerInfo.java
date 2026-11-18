package lms.sales.invoicing.domain;

import java.util.regex.Pattern;
import lms.sales.shared.domain.SalesException;

/**
 * Thông tin người mua. Không bắt buộc: khách lẻ không lấy hóa đơn thì ghi {@link #NOT_REQUESTED_LABEL} và không có MST.
 * Có MST thì bắt buộc tên đơn vị và địa chỉ. Dữ liệu cá nhân: {@code toString} không in.
 */
public record BuyerInfo(String name, String company, String taxCode, String address, String email) {

    /** Cách ghi cho khách lẻ không lấy hóa đơn (cần kế toán xác nhận, ADR-0030 mục 2.9). */
    public static final String NOT_REQUESTED_LABEL = "Người mua không lấy hóa đơn";

    public static final int MAX_NAME = 255;
    public static final int MAX_ADDRESS = 500;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Khách lẻ không yêu cầu hóa đơn. */
    public static BuyerInfo notRequested() {
        return new BuyerInfo(NOT_REQUESTED_LABEL, null, null, null, null);
    }

    /** Chuẩn hóa và kiểm: cắt khoảng trắng, rỗng thành null, MST đúng định dạng, có MST thì cần đơn vị và địa chỉ. */
    public static BuyerInfo of(String name, String company, String taxCode, String address, String email) {
        String tax = TaxCode.buyerOrNull(taxCode);
        BuyerInfo b = new BuyerInfo(clean(name, MAX_NAME, "Tên người mua"), clean(company, MAX_NAME, "Tên đơn vị"),
                tax, clean(address, MAX_ADDRESS, "Địa chỉ"), cleanEmail(email));
        if (tax != null && (b.company == null || b.address == null)) {
            throw SalesException.invalidRequest("Có mã số thuế thì bắt buộc nhập tên đơn vị và địa chỉ");
        }
        return b;
    }

    /** Yêu cầu xuất hóa đơn phải nêu được người mua (tên cá nhân hoặc tên đơn vị). */
    public BuyerInfo requireIdentified() {
        if (name == null && company == null) {
            throw SalesException.invalidRequest("Cần tên người mua hoặc tên đơn vị");
        }
        return this;
    }

    public boolean isNotRequested() {
        return NOT_REQUESTED_LABEL.equals(name) && company == null && taxCode == null;
    }

    private static String clean(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw SalesException.invalidRequest(label + " tối đa " + max + " ký tự");
        }
        return v;
    }

    private static String cleanEmail(String value) {
        String v = clean(value, 255, "Email");
        if (v != null && !EMAIL.matcher(v).matches()) {
            throw SalesException.invalidRequest("Email không hợp lệ");
        }
        return v;
    }

    @Override
    public String toString() {
        return "BuyerInfo[***]";
    }
}
