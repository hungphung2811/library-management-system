package lms.identity.accesscontrol.domain;

public class AccessControlNotFoundException extends RuntimeException {
    public AccessControlNotFoundException(String message) {
        super(message);
    }
}
