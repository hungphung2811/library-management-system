package lms.api.circulation.dto;

import java.util.UUID;

/**
 * Query GET /api/borrowings/search, đều tùy chọn; chuyển nguyên xuống service (service kiểm giá trị).
 * status: ACTIVE hoặc RETURNED; overdue=true: phiếu còn bản sao chưa trả và quá hạn; page từ 0, size 1-100.
 * tenantId chỉ có tác dụng với platform admin không tenant (ADR-0008).
 */
public record BorrowingSearchQuery(String status, Boolean overdue, UUID readerId, Integer page, Integer size,
                                   String tenantId) {
}
