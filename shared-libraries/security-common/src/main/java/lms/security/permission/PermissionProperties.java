package lms.security.permission;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình tiền tố {@code lms.security.permission}. */
@ConfigurationProperties(prefix = "lms.security.permission")
public class PermissionProperties {

    /** Tắt hẳn kiểm quyền theo endpoint (chỉ dùng cho profile dev). */
    private boolean enabled = true;
    /** Bắt buộc token có claim tenantId trên endpoint thường (403 TENANT_REQUIRED); mặc định tắt cho tới khi service sẵn sàng. */
    private boolean requireTenant = false;
    /** Tên service ghi vào danh mục permission, thường lấy từ spring.application.name. */
    private String serviceName = "unknown-service";
    /** Địa chỉ identity-service để đăng ký khóa; bỏ trống thì không đăng ký (chỉ cảnh báo). */
    private String identityBaseUrl = "";
    /** Khóa chia sẻ cho API nội bộ của identity-service. */
    private String internalApiKey = "";
    /** Sau lần đăng ký lỗi, chờ bao lâu mới thử lại ở lần gọi sau. */
    private Duration registrationCooldown = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);
    /** Đường dẫn không đi qua interceptor. */
    private List<String> excludePaths = List.of("/error", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**",
            "/swagger-ui.html");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRequireTenant() { return requireTenant; }
    public void setRequireTenant(boolean requireTenant) { this.requireTenant = requireTenant; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getIdentityBaseUrl() { return identityBaseUrl; }
    public void setIdentityBaseUrl(String identityBaseUrl) { this.identityBaseUrl = identityBaseUrl; }
    public String getInternalApiKey() { return internalApiKey; }
    public void setInternalApiKey(String internalApiKey) { this.internalApiKey = internalApiKey; }
    public Duration getRegistrationCooldown() { return registrationCooldown; }
    public void setRegistrationCooldown(Duration registrationCooldown) { this.registrationCooldown = registrationCooldown; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public List<String> getExcludePaths() { return excludePaths; }
    public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths; }
}
