package lms.sales.ordering.infrastructure.web;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditQueryPort;
import lms.sales.shared.application.SalesAuditQueryUseCase;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nhật ký bán hàng (nguồn chuẩn sales_audit_log); chỉ đọc. from bao gồm, to loại trừ (ISO-8601). */
@RestController
@RequestMapping("/internal/sales/audit-log")
@RequiresModule(TenantModule.SALES)
public class SalesAuditController {

    public record AuditSearchQuery(String action, String aggregateType, UUID aggregateId, Instant from, Instant to,
                                   Integer page, Integer size) {
    }

    private final SalesAuditQueryUseCase audit;

    public SalesAuditController(SalesAuditQueryUseCase audit) {
        this.audit = audit;
    }

    @GetMapping
    public PageResult<SalesAuditQueryPort.Entry> search(AuditSearchQuery q) {
        return audit.search(RequestSupport.tenant(), q.action(), q.aggregateType(), q.aggregateId(), q.from(),
                q.to(), RequestSupport.page(q.page()), RequestSupport.size(q.size()));
    }
}
