package lms.identity.accesscontrol.domain;

/** Người gọi không đủ thẩm quyền cho thao tác này dù đã qua kiểm tra permission (vd. leo thang lên role quản trị). */
public class AccessControlForbiddenException extends RuntimeException {
    public AccessControlForbiddenException(String message) {
        super(message);
    }
}
