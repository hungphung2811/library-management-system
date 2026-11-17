package lms.identity.auth.domain;

/** Lý do một refresh token bị thu hồi (ADR-0017); lưu ở cột {@code revoked_reason}. */
public enum RevokeReason {
    /** Bị xoay vòng (refresh hoặc đổi tenant); token cũ nằm trong cửa sổ ân hạn thì không thu hồi họ khi bị dùng lại. */
    ROTATED,
    /** Người dùng đăng xuất. */
    LOGOUT,
    /** Người dùng hoặc quản trị viên thu hồi một phiên / nhiều phiên. */
    SESSION_REVOKED,
    /** Dùng lại token đã thu hồi ngoài cửa sổ ân hạn: thu hồi cả họ. */
    REUSE_DETECTED,
    /** Thu hồi hàng loạt (đổi mật khẩu, đặt lại mật khẩu, mất role, dữ liệu cũ trước ADR-0017). */
    BULK;

    /** Giá trị lạ hoặc null cho null (dòng chưa ghi lý do). */
    public static RevokeReason parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
