package lms.sales.ordering.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Tra tenant của một lệnh thanh toán theo mã tham chiếu mà KHÔNG cần ngữ cảnh tenant (webhook của cổng không mang JWT).
 * Chỉ trả tenantId (không đọc dữ liệu khác); người gọi phải đã xác thực chữ ký trước, rồi mới đặt TenantContext theo
 * kết quả và đọc lại lệnh trong đúng tenant (ADR-0020).
 */
public interface PaymentTenantResolverPort {

    Optional<UUID> findTenantIdByReference(String reference);
}
