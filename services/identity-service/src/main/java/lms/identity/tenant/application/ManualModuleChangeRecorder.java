package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Cổng ra (tùy chọn) để context hợp đồng ghi lịch sử "ngoại lệ ngoài hợp đồng" khi platform admin bật/tắt module tay
 * (ADR-0012 mục 2.3.4, R14). Gọi trong CÙNG transaction và cùng {@code TenantContext} với thay đổi module, chỉ khi có
 * thay đổi thật. Không có adapter thì đường tay chạy như cũ.
 */
public interface ManualModuleChangeRecorder {

    void recordManualChange(UUID tenantId, String module, boolean enabled, UUID actorId, Instant at);
}
