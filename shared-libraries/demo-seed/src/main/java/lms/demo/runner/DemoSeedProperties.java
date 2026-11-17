package lms.demo.runner;

import lms.demo.plan.DemoPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình {@code lms.demo-seed.*}. {@code creates-tenant=true} ở identity (tự tạo tenant nên không cần {@code tenant-id}).
 * Không có mật khẩu mặc định trong mã.
 */
@ConfigurationProperties("lms.demo-seed")
public record DemoSeedProperties(
        boolean enabled,
        String tenantId,
        String password,
        boolean createsTenant,
        @DefaultValue("" + DemoPlan.DEFAULT_SEED) long seed) {
}
