package lms.circulation.policy.domain;

/** Chính sách mượn trả có giá trị ngoài khoảng cho phép. */
public class InvalidCirculationPolicyException extends RuntimeException {
    public InvalidCirculationPolicyException(String message) {
        super(message);
    }
}
