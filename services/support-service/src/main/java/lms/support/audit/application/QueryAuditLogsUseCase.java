package lms.support.audit.application;

import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.domain.AuditLogPage;
import lms.support.audit.domain.AuditLogRepository;
import org.springframework.transaction.annotation.Transactional;

public class QueryAuditLogsUseCase {

    private final AuditLogRepository repository;

    public QueryAuditLogsUseCase(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(transactionManager = AuditTransactions.MANAGER, readOnly = true)
    public PageResponse<AuditLogResponse> execute(AuditLogCriteria criteria) {
        AuditLogPage page = repository.search(criteria);
        return new PageResponse<>(page.items().stream().map(AuditLogResponse::from).toList(),
                page.total(), page.page(), page.size());
    }
}
