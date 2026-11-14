package lms.identity.accesscontrol.domain;

public class InvalidAccessControlException extends RuntimeException {
    public InvalidAccessControlException(String message) {
        super(message);
    }
}
