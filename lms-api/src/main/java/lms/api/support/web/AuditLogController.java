package lms.api.support.web;

import lms.api.support.client.SupportClient;
import lms.api.support.dto.AuditLogView;
import lms.api.support.dto.PageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: tra cứu nhật ký kiểm toán. Quyền do support-service kiểm. */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final SupportClient support;

    public AuditLogController(SupportClient support) {
        this.support = support;
    }

    /** Tham số lọc và phân trang; Spring tự bind từ query string. */
    public record AuditLogQuery(String aggregateId, String eventType, Integer page, Integer size) {
    }

    @GetMapping
    public PageView<AuditLogView> search(AuditLogQuery query) {
        int page = query.page() == null ? 0 : query.page();
        int size = query.size() == null ? 20 : query.size();
        return support.searchAuditLogs(query.aggregateId(), query.eventType(), page, size);
    }
}
