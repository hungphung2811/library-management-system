package lms.identity.auth.infrastructure.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình đăng nhập ngoài (auth.external.*): mỗi nhà cung cấp OIDC là một mục trong providers. */
@ConfigurationProperties(prefix = "auth.external")
public class ExternalAuthProperties {

    /** URL công khai của /api/auth qua gateway; redirect_uri = publicBaseUrl + /external/{provider}/callback. */
    private String publicBaseUrl = "http://localhost:8080/api/auth";
    /** Origin của frontend được phép làm returnUrl (so khớp chính xác scheme://host[:port]). Rỗng = từ chối tất cả. */
    private List<String> allowedReturnOrigins = new ArrayList<>();
    /** Khóa HMAC ký cookie trạng thái. Để trống thì sinh khóa tạm mỗi lần chạy (lượt đang dở hỏng khi restart; nhiều instance phải dùng chung khóa). */
    private String stateSecret = "";
    private boolean cookieSecure = false;
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public List<String> getAllowedReturnOrigins() { return allowedReturnOrigins; }
    public void setAllowedReturnOrigins(List<String> allowedReturnOrigins) { this.allowedReturnOrigins = allowedReturnOrigins; }
    public String getStateSecret() { return stateSecret; }
    public void setStateSecret(String stateSecret) { this.stateSecret = stateSecret; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }
    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    public static class Provider {
        private String displayName;
        private String authority;
        private String clientId;
        private String clientSecret;
        private String scopes = "openid email profile";
        private List<String> allowedEmailDomains = new ArrayList<>();

        /** Thiếu authority hoặc clientId thì nhà cung cấp bị tắt. */
        public boolean isConfigured() {
            return authority != null && !authority.isBlank() && clientId != null && !clientId.isBlank();
        }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getAuthority() { return authority; }
        public void setAuthority(String authority) { this.authority = authority; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }
        public List<String> getAllowedEmailDomains() { return allowedEmailDomains; }
        public void setAllowedEmailDomains(List<String> allowedEmailDomains) { this.allowedEmailDomains = allowedEmailDomains; }
    }
}
