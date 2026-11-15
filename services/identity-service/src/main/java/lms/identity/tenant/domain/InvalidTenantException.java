package lms.identity.tenant.domain;

public class InvalidTenantException extends RuntimeException {
    public InvalidTenantException(String message) {
        super(message);
    }
}
