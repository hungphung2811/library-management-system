package lms.sales.shared.application;

import java.util.Map;

/**
 * Bổ sung trường cho sự kiện phát lên sales.events (ADR-0030 gói D) mà KHÔNG ghi vào {@code sales_audit_log}. Chỉ
 * trả id, mã, số tiền, mã trạng thái: tuyệt đối không email, điện thoại, tên, địa chỉ. Trường không có giá trị thì
 * vắng mặt trong kết quả (không có giá trị null). Được gọi đúng một lần cho mỗi dòng audit, trong transaction của
 * thao tác nghiệp vụ.
 */
public interface SalesEventEnrichmentPort {

    /** Trường thêm cho sự kiện của dòng audit này; rỗng nếu loại dòng audit không cần làm giàu. */
    Map<String, Object> enrich(SalesAuditPort.AuditEntry entry);

    /** Cổng không làm gì (dùng khi dựng thủ công, test). */
    SalesEventEnrichmentPort NONE = entry -> Map.of();
}
