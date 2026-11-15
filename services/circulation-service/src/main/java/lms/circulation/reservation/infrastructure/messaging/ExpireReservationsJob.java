package lms.circulation.reservation.infrastructure.messaging;

import java.util.List;
import java.util.UUID;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UC14: bộ hẹn giờ chạy đầu mỗi ngày, lần lượt từng tenant ACTIVE (mỗi tenant một TenantContext riêng).
 * Chạy trùng giữa nhiều instance vẫn an toàn vì use case chỉ chọn đặt trước đã quá hạn. Không có tenant nào thì
 * không chạy gì (không bao giờ chạy ngoài phạm vi tenant).
 */
@Component
// Adapter của ActiveTenantsPort (bảng known_tenants) do Task 8 cung cấp; chưa có thì job không được tạo.
@ConditionalOnBean(ActiveTenantsPort.class)
public class ExpireReservationsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireReservationsJob.class);

    private final ExpireOverdueReservationsUseCase expire;
    private final ActiveTenantsPort activeTenants;

    public ExpireReservationsJob(ExpireOverdueReservationsUseCase expire, ActiveTenantsPort activeTenants) {
        this.expire = expire;
        this.activeTenants = activeTenants;
    }

    @Scheduled(cron = "${app.reservation.expire-cron:0 5 0 * * *}")
    public void run() {
        List<UUID> tenantIds = activeTenants.activeTenantIds();
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
