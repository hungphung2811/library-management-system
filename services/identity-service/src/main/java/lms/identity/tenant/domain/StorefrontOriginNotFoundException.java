package lms.identity.tenant.domain;

/** Không có origin với id đó trong tenant (gồm id thuộc tenant khác: không phân biệt, ADR-0013 AC13). */
public class StorefrontOriginNotFoundException extends RuntimeException {

    public StorefrontOriginNotFoundException(String message) {
        super(message);
    }
}
