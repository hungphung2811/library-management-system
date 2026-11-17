package lms.sales.shared.application;

import java.util.List;
import java.util.UUID;

/**
 * Danh bạ tenant của sales-service (bảng sales_tenants, không RLS; ADR-0010 mục 2.8). Ghi "chèn nếu chưa có" trong
 * transaction tạo đơn; chỉ job ẩn danh đọc. Không chứa dữ liệu cá nhân.
 */
public interface TenantDirectoryPort {

    void touch(UUID tenantId);

    List<UUID> listAll();
}
