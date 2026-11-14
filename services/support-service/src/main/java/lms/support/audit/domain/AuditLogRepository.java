package lms.support.audit.domain;

/** Truy vấn audit log (ghi nằm ở port SaveAuditLogPort của tầng application). */
public interface AuditLogRepository {

    AuditLogPage search(AuditLogCriteria criteria);
}
