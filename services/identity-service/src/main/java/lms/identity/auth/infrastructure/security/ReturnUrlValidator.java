package lms.identity.auth.infrastructure.security;

import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Chỉ cho quay về origin của frontend đã khai báo (chống open redirect): phải là http/https tuyệt đối, có host,
 * không có userinfo và fragment, và scheme://host[:port] trùng khớp chính xác một origin trong danh sách.
 */
public class ReturnUrlValidator {

    private final List<String> allowedOrigins;

    public ReturnUrlValidator(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(o -> o.trim().toLowerCase(Locale.ROOT)).toList();
    }

    public void validate(String returnUrl) {
        if (!isAllowed(returnUrl)) {
            throw new ExternalAuthException(Reason.RETURN_URL_NOT_ALLOWED, "returnUrl không thuộc origin được phép");
        }
    }

    public boolean isAllowed(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) return false;
        URI uri;
        try {
            uri = new URI(returnUrl.trim());
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return false;
        String origin = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return allowedOrigins.contains(origin);
    }
}
