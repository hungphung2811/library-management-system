package lms.identity.auth.domain;

import java.util.Locale;

/**
 * Kênh phát hành phiên (customer-api-data-authorization 5.3 phương án C). STAFF: đăng nhập qua lms-api (ứng dụng nhân
 * viên). STOREFRONT: đăng nhập qua customer-api (website khách, mã chạy trong trình duyệt của website do tenant kiểm
 * soát), token bị thu hẹp (không admin/platformAdmin) và mang aud riêng. Kênh gắn vào refresh token để làm mới giữ kênh.
 */
public enum TokenChannel {
    STAFF,
    STOREFRONT;

    /** null hoặc rỗng = STAFF (hành vi cũ); giá trị lạ thì ném IllegalArgumentException (không đoán kênh). */
    public static TokenChannel parse(String value) {
        if (value == null || value.isBlank()) return STAFF;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Kênh phiên không hợp lệ: " + value);
        }
    }
}
