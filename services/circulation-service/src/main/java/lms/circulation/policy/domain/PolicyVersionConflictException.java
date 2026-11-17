package lms.circulation.policy.domain;

/** Chính sách đã bị người khác sửa (version không khớp). */
public class PolicyVersionConflictException extends RuntimeException {
    public PolicyVersionConflictException(String message) {
        super(message);
    }
}
