package lms.identity.tenant.domain;

import java.util.UUID;

/** Origin đã tồn tại (duy nhất toàn hệ thống, ADR-0013 C1). ownerTenantId null khi không biết chủ (đua ghi đồng thời). */
public class StorefrontOriginDuplicatedException extends RuntimeException {

    private final UUID ownerTenantId;

    public StorefrontOriginDuplicatedException(String message, UUID ownerTenantId) {
        super(message);
        this.ownerTenantId = ownerTenantId;
    }

    public UUID ownerTenantId() {
        return ownerTenantId;
    }
}
