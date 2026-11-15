package lms.identity.user.domain;

/**
 * Quản trị viên tenant không được thao tác trên tài khoản này (mục 5.6 spec multi-tenant): tài khoản thuộc từ hai tenant
 * trở lên hoặc là platform admin thì việc sửa họ tên/email, đặt lại mật khẩu, bật/tắt tài khoản thuộc về chính người dùng
 * (và người vận hành). HTTP 403.
 */
public class UserOperationNotAllowedException extends RuntimeException {
    public UserOperationNotAllowedException(String message) {
        super(message);
    }
}
