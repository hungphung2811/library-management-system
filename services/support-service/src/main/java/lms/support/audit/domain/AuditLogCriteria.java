package lms.support.audit.domain;

/** Điều kiện tìm audit log: lọc tùy chọn theo aggregateId, eventType; phân trang có giới hạn. */
public record AuditLogCriteria(String aggregateId, String eventType, int page, int size) {

    public static final int MAX_PAGE_SIZE = 100;

    public AuditLogCriteria {
        if (page < 0) throw new InvalidAuditQueryException("page phải >= 0");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidAuditQueryException("size phải trong khoảng 1-" + MAX_PAGE_SIZE);
        }
        aggregateId = blankToNull(aggregateId);
        eventType = blankToNull(eventType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
