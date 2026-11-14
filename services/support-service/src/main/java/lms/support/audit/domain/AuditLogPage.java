package lms.support.audit.domain;

import java.util.List;

public record AuditLogPage(List<AuditLog> items, long total, int page, int size) {

    public AuditLogPage {
        items = List.copyOf(items);
    }
}
