package lms.demo.runner;

import java.time.Clock;
import java.util.UUID;
import lms.demo.plan.DemoPlan;

/**
 * Đầu vào của mỗi bước seed. {@code tenantId} là null chỉ ở service tự tạo tenant (identity).
 * {@code password} là mật khẩu demo (lấy từ cấu hình, không có mặc định trong mã).
 */
public record DemoSeedContext(UUID tenantId, DemoPlan plan, String password, Clock clock) {
}
