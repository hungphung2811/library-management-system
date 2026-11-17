package lms.api.dashboard.client;

import java.util.Set;
import java.util.UUID;

/** Module đang bật của một tenant ({@code GET /api/platform/tenants/{id}/modules} của identity, dành cho platform admin). */
public interface PlatformModulesClient {

    /** Mã module viết hoa đang bật (ví dụ LENDING, SALES). */
    Set<String> enabledModules(UUID tenantId);
}
