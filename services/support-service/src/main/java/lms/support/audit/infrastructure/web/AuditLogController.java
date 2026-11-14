package lms.support.audit.infrastructure.web;

import lms.support.audit.application.AuditLogResponse;
import lms.support.audit.application.PageResponse;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.domain.AuditLogCriteria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final QueryAuditLogsUseCase query;

    public AuditLogController(QueryAuditLogsUseCase query) {
        this.query = query;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> search(@RequestParam(required = false) String aggregateId,
                                                 @RequestParam(required = false) String eventType,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return query.execute(new AuditLogCriteria(aggregateId, eventType, page, size));
    }
}
