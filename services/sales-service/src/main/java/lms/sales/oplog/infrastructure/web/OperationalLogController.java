package lms.sales.oplog.infrastructure.web;

import java.time.Instant;
import java.util.UUID;
import lms.sales.oplog.application.OperationalLogQueryUseCase;
import lms.sales.oplog.application.OperationalLogQueryUseCase.Filter;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.CrossTenantReadable;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import lms.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xem log vận hành đã lưu (ADR-0029): tab Tích hợp (gọi ra + gọi vào) và tab Hệ thống. Chỉ đọc, mới nhất trước. Khóa quyền:
 * OperationalLogController/integration, OperationalLogController/system. Cách ly tenant do RLS bảo đảm; thêm lọc tường
 * minh theo tenant của request. Platform admin: có header {@code X-Tenant-Id} thì chỉ tenant đó, không có thì (nhờ
 * {@link CrossTenantReadable}) xem mọi tenant kể cả dòng chưa biết tenant, và có thể lọc bằng query {@code tenantId}.
 */
@RestController
@RequestMapping("/internal/sales/logs")
@RequiresModule(TenantModule.SALES)
public class OperationalLogController {

    /** Query chung hai tab; {@code channel} (OUTBOUND, INBOUND) chỉ có nghĩa ở tab tích hợp. */
    public record LogQuery(String channel, String provider, String outcome, String level, Boolean anomalyOnly,
                           Instant from, Instant to, UUID tenantId, Integer page, Integer size) {
    }

    public record LogEventView(UUID id, Instant occurredAt, String channel, String provider, String event,
                               String level, String op, String outcome, Integer httpStatus, Long durationMs,
                               String errorKind, String errorClass, String anomalyType, String correlationId,
                               UUID intentId, String reference, UUID tenantId) {

        static LogEventView of(OperationalLogEvent e) {
            return new LogEventView(e.id(), e.occurredAt(), e.channel(), e.provider(), e.event(), e.level(), e.op(),
                    e.outcome(), e.httpStatus(), e.durationMs(), e.errorKind(), e.errorClass(), e.anomalyType(),
                    e.correlationId(), e.intentId(), e.reference(), e.tenantId());
        }
    }

    private final OperationalLogQueryUseCase logs;

    public OperationalLogController(OperationalLogQueryUseCase logs) {
        this.logs = logs;
    }

    @GetMapping("/integration")
    @CrossTenantReadable
    public PageResult<LogEventView> integration(LogQuery q) {
        return logs.integration(filter(q), RequestSupport.page(q.page()), RequestSupport.size(q.size()))
                .map(LogEventView::of);
    }

    @GetMapping("/system")
    @CrossTenantReadable
    public PageResult<LogEventView> system(LogQuery q) {
        return logs.system(filter(q), RequestSupport.page(q.page()), RequestSupport.size(q.size()))
                .map(LogEventView::of);
    }

    /** Tenant của request luôn thắng query {@code tenantId}; chỉ phạm vi ALL (platform) mới dùng query. */
    private static Filter filter(LogQuery q) {
        UUID tenant = TenantContext.current().orElse(TenantContext.isAllTenants() ? q.tenantId() : null);
        return new Filter(tenant, q.channel(), q.provider(), q.outcome(), q.level(), Boolean.TRUE.equals(q.anomalyOnly()),
                q.from(), q.to());
    }
}
