package lms.identity.tenant.domain;

public class DuplicateTenantException extends RuntimeException {
    public DuplicateTenantException(String message) {
        super(message);
    }
}
