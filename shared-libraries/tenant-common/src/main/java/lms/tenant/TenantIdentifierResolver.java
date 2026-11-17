package lms.tenant;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Cho Hibernate biết tenant hiện tại. Không có tenant thì trả UUID nil để truy vấn không khớp dòng nào (fail-closed).
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NIL_TENANT = new UUID(0L, 0L);

    /** UUID canh gác cho phạm vi đọc ALL (ADR-0008); khác {@link #NIL_TENANT}, không phải tenant thật. */
    public static final UUID ROOT = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isAllTenants()) return ROOT;
        return TenantContext.current().orElse(NIL_TENANT);
    }

    /** Hibernate bỏ điều kiện @TenantId cho truy vấn đọc khi nhận ROOT. */
    @Override
    public boolean isRoot(UUID tenantId) {
        return ROOT.equals(tenantId);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
