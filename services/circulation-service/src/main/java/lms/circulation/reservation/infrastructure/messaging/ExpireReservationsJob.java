package lms.circulation.reservation.infrastructure.messaging;

import java.util.List;
import java.util.UUID;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UC14: bộ hẹn giờ chạy đầu mỗi ngày, lần lượt từng tenant ACTIVE (mỗi tenant một TenantContext riêng).
 * Chạy trùng giữa nhiều instance vẫn an toàn vì use case chỉ chọn đặt trước đã quá hạn. Không có tenant nào thì
 * không chạy gì (không bao giờ chạy ngoài phạm vi tenant).
 */
@Component
public class ExpireReservationsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireReservationsJob.class);

    private final ExpireOverdueReservationsUseCase expire;
    private final ObjectProvider<ActiveTenantsPort> activeTenants;

    // Adapter duy nhất của ActiveTenantsPort là KnownTenantsJdbcAdapter (bảng known_tenants, context tenant); vẫn lấy
    // qua ObjectProvider để context thiếu adapter (test lát cắt) chỉ log WARN và bỏ qua.
    public ExpireReservationsJob(ExpireOverdueReservationsUseCase expire, ObjectProvider<ActiveTenantsPort> activeTenants) {
        this.expire = expire;
        this.activeTenants = activeTenants;
    }

    @Scheduled(cron = "${app.reservation.expire-cron:0 5 0 * * *}")
    public void run() {
        ActiveTenantsPort port = activeTenants.getIfAvailable();
        if (port == null) {
            log.warn("UC14: ActiveTenantsPort chưa được cấu hình, bỏ qua lượt hết hạn đặt trước");
            return;
        }
        List<UUID> tenantIds = port.activeTenantIds();
        if (tenantIds.isEmpty()) {
            log.info("UC14: không có tenant ACTIVE, bỏ qua");
            return;
        }
        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.runAs(tenantId, () -> {
                    int processed = expire.execute();
                    log.info("UC14: tenant {} đã xử lý {} đặt trước quá hạn giữ", tenantId, processed);
                });
            } catch (RuntimeException e) {
                log.error("UC14: lỗi khi xử lý tenant {}, tiếp tục tenant khác", tenantId, e);
            }
        }
    }
}
