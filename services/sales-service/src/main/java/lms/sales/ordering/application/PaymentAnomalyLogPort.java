package lms.sales.ordering.application;

import java.util.UUID;

/**
 * Cổng ghi LOG MÁY cho bất thường thanh toán (ADR-0029). Cài đặt ở infrastructure (kênh, che dữ liệu, giảm trùng); phía
 * application chỉ nói "có bất thường này". Không ném exception ra ngoài trừ lỗi lập trình; không ghi DB.
 */
public interface PaymentAnomalyLogPort {

    /** Ghi một bất thường (WARN/ERROR, {@code anomaly=true}) ở kênh theo loại, có giảm trùng theo khóa. */
    void log(PaymentAnomaly anomaly);

    /** Cổng hồi phục sau chuỗi lỗi liên tiếp: một dòng INFO {@code outcome=RECOVERED failures=N}. */
    void recovered(String provider, int failures);

    /** Xung đột phiên bản/đồng thời với hủy: không phải bất thường, một dòng INFO {@code outcome=CONFLICT}. */
    void conflict(PaymentAnomalySource source, String provider, UUID tenantId, UUID intentId, String reference);
}
