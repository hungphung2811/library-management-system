package lms.identity.tenantdata.infrastructure.client;

import lms.identity.tenant.application.ListActiveTenantAdminsQuery;
import lms.identity.tenant.application.TenantNameQuery;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenantdata.application.TenantAdminsPort;
import lms.identity.tenantdata.application.TenantLookupPort;
import lms.tenant.TenantContext;

/** Nối context tenantdata với tầng application của context tenant (chỉ dùng application, đúng luật kiến trúc). */
public final class TenantContextAdapters {

    private TenantContextAdapters() {
    }

    public static TenantLookupPort lookup(TenantNameQuery names) {
        return names::codeOf;
    }

    public static TenantAdminsPort admins(ListActiveTenantAdminsQuery admins, TransactionalRunner tx) {
        return tenantId -> TenantContext.callAs(tenantId, () -> tx.inTransaction(() -> admins.execute(tenantId)));
    }
}
