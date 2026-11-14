package lms.support.audit.application;

import lms.support.audit.domain.AuditLog;

public interface SaveAuditLogPort {

    /** Ghi nguyên tử và idempotent theo eventId. Trả true nếu bản ghi mới được tạo, false nếu đã có. */
    boolean saveIfAbsent(AuditLog auditLog);
}
