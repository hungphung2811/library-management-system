package lms.identity.contract.application;

import java.util.UUID;
import java.util.function.Function;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.TransactionalRunner;
import lms.tenant.TenantContext;

/**
 * Khung chạy một thao tác ghi của hợp đồng (ADR-0012 mục 2.4): đặt {@code TenantContext.callAs(tenant)} TRƯỚC, mở MỘT
 * transaction, khóa dòng tenant (cùng khóa của bật/tắt tay và suspend nên mọi thao tác trên tenant tuần tự hóa), chạy
 * công việc, rồi SAU commit mới phát event gom được.
 */
final class ContractExecutor {

    private final TenantModuleOperations ops;
    private final TransactionalRunner tx;
    private final PublishPlatformEventPort events;

    ContractExecutor(TenantModuleOperations ops, TransactionalRunner tx, PublishPlatformEventPort events) {
        this.ops = ops;
        this.tx = tx;
        this.events = events;
    }

    <T> T inTenantTransaction(UUID tenantId, Function<EventBuffer, T> work) {
        EventBuffer buffer = new EventBuffer();
        return TenantContext.callAs(tenantId, () -> {
            T result = tx.inTransaction(() -> {
                ops.lockTenant(tenantId).orElseThrow(
                        () -> new ContractException(Reason.TENANT_NOT_FOUND, "Không tìm thấy tenant " + tenantId));
                return work.apply(buffer);
            });
            buffer.publishTo(events);
            return result;
        });
    }
}
