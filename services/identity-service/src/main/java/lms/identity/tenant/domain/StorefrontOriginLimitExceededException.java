package lms.identity.tenant.domain;

/** Tenant đã đủ số origin tối đa (đếm cả dòng đang tắt). */
public class StorefrontOriginLimitExceededException extends RuntimeException {

    public StorefrontOriginLimitExceededException(int max) {
        super("Mỗi tenant tối đa " + max + " origin storefront");
    }
}
