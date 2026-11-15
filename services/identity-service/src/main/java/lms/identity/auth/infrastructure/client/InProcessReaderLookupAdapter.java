package lms.identity.auth.infrastructure.client;

import lms.identity.auth.application.ReaderLookupPort;
import lms.identity.tenant.application.ListActiveTenantIdsUseCase;
import lms.identity.user.application.FindReaderUserIdsByEmailUseCase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.tenant.TenantContext;

/**
 * Gọi API (tầng application) của context user trong cùng service. Hồ sơ độc giả là dữ liệu thuộc tenant (RLS +
 * @TenantId) và lúc tra chưa biết tenant, nên tra lần lượt trong TenantContext của từng tenant ACTIVE rồi gộp lại.
 */
public class InProcessReaderLookupAdapter implements ReaderLookupPort {

    private final FindReaderUserIdsByEmailUseCase findByEmail;
    private final ListActiveTenantIdsUseCase activeTenants;

    public InProcessReaderLookupAdapter(FindReaderUserIdsByEmailUseCase findByEmail,
                                        ListActiveTenantIdsUseCase activeTenants) {
        this.findByEmail = findByEmail;
        this.activeTenants = activeTenants;
    }

    @Override
    public List<UUID> findUserIdsByEmail(String email) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (UUID tenantId : activeTenants.execute()) {
            userIds.addAll(TenantContext.callAs(tenantId, () -> findByEmail.execute(email)));
        }
        return List.copyOf(userIds);
    }
}
