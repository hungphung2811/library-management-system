package lms.identity.accesscontrol.domain;

public class DuplicateAccessControlException extends RuntimeException {
    public DuplicateAccessControlException(String message) {
        super(message);
    }
}
