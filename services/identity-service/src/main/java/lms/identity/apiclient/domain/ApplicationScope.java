package lms.identity.apiclient.domain;

import java.util.Arrays;
import lms.identity.apiclient.domain.ApplicationException.Reason;

/**
 * Danh mục scope tích hợp (hợp đồng công khai có phiên bản, không phải khóa quyền nội bộ ADR-0003). Danh mục khớp CHECK
 * của application_scopes. Quyết định Q5/Q6: pha đầu chỉ CẤP scope đọc cho books, inventory, orders; scope ghi và
 * {@code customers:*} có trong danh mục nhưng bị từ chối khi cấp ({@link #grantable()} = false) tới khi có quyết định.
 */
public enum ApplicationScope {
    BOOKS_READ("books:read", true),
    BOOKS_WRITE("books:write", false),
    INVENTORY_READ("inventory:read", true),
    INVENTORY_WRITE("inventory:write", false),
    ORDERS_READ("orders:read", true),
    ORDERS_WRITE("orders:write", false),
    CUSTOMERS_READ("customers:read", false),
    CUSTOMERS_WRITE("customers:write", false);

    private final String code;
    private final boolean grantable;

    ApplicationScope(String code, boolean grantable) {
        this.code = code;
        this.grantable = grantable;
    }

    public String code() {
        return code;
    }

    /** Pha này có được cấp scope này không. */
    public boolean grantable() {
        return grantable;
    }

    /** @throws ApplicationException INVALID_SCOPE nếu không có trong danh mục */
    public static ApplicationScope parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        return Arrays.stream(values()).filter(s -> s.code.equals(value)).findFirst().orElseThrow(
                () -> new ApplicationException(Reason.INVALID_SCOPE, "Scope không hợp lệ: " + safe(value)));
    }

    /** @throws ApplicationException SCOPE_NOT_GRANTABLE nếu scope chưa được cấp ở pha này */
    public void requireGrantable() {
        if (!grantable) {
            throw new ApplicationException(Reason.SCOPE_NOT_GRANTABLE,
                    "Scope " + code + " chưa được cấp ở giai đoạn này (chỉ cấp books:read, inventory:read, orders:read)");
        }
    }

    private static String safe(String value) {
        String cut = value.length() > 40 ? value.substring(0, 40) : value;
        return cut.replaceAll("[\\p{Cntrl}]", "?");
    }
}
