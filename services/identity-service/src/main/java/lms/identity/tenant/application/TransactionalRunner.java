package lms.identity.tenant.application;

import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chạy một khối việc trong MỘT transaction. Dùng khi transaction phải bắt đầu SAU khi đã đặt tenant
 * (TenantContext.callAs): kết nối DB nhận {@code app.tenant_id} lúc được mượn ở đầu transaction (RLS FORCE), Hibernate
 * lấy tenant lúc mở session; đổi tenant giữa chừng transaction không có tác dụng. Bean tạo trong
 * TenantBeanConfiguration (proxy Spring); unit test dùng thẳng (không có transaction).
 */
public class TransactionalRunner {

    @Transactional
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
