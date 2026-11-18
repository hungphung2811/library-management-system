package lms.sales.invoicing.domain;

import java.util.regex.Pattern;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Mã số thuế Việt Nam: chỉ kiểm ĐỊNH DẠNG (không kiểm tồn tại với cơ quan thuế, không kiểm chữ số kiểm tra).
 * Người bán: 10 chữ số hoặc 10 chữ số, dấu gạch, 3 chữ số (đơn vị phụ thuộc). Người mua thêm 12 chữ số (MST cá nhân là
 * số định danh). Sai định dạng thì 400 INVALID_TAX_CODE.
 */
public final class TaxCode {

    private static final Pattern SELLER = Pattern.compile("^[0-9]{10}(-[0-9]{3})?$");
    private static final Pattern BUYER = Pattern.compile("^([0-9]{10}(-[0-9]{3})?|[0-9]{12})$");

    private TaxCode() {
    }

    /** Cắt khoảng trắng; sai định dạng người bán thì INVALID_TAX_CODE. Không nhận rỗng. */
    public static String seller(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!SELLER.matcher(s).matches()) {
            throw new SalesException(ErrorCode.INVALID_TAX_CODE,
                    "Mã số thuế người bán phải gồm 10 chữ số hoặc 10-3 chữ số");
        }
        return s;
    }

    /** Rỗng thì null (người mua không có MST); có giá trị mà sai định dạng thì INVALID_TAX_CODE. */
    public static String buyerOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (!BUYER.matcher(s).matches()) {
            throw new SalesException(ErrorCode.INVALID_TAX_CODE,
                    "Mã số thuế người mua phải gồm 10 chữ số, 10-3 chữ số hoặc 12 chữ số");
        }
        return s;
    }
}
