package lms.identity.auth.infrastructure.security;

import lms.identity.auth.application.ExternalProviderPolicyPort;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties.Provider;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Tra cứu nhà cung cấp đã cấu hình đủ (authority + clientId) và chính sách tên miền email. */
public class ExternalProviderRegistry implements ExternalProviderPolicyPort {

    public record ProviderInfo(String name, String displayName) {
    }

    private final ExternalAuthProperties properties;

    public ExternalProviderRegistry(ExternalAuthProperties properties) {
        this.properties = properties;
    }

    public Optional<Provider> find(String name) {
        if (name == null) return Optional.empty();
        Provider p = properties.getProviders().get(name.toLowerCase(Locale.ROOT));
        return p != null && p.isConfigured() ? Optional.of(p) : Optional.empty();
    }

    public List<ProviderInfo> list() {
        return properties.getProviders().entrySet().stream()
                .filter(e -> e.getValue().isConfigured())
                .map(e -> new ProviderInfo(e.getKey(),
                        e.getValue().getDisplayName() == null || e.getValue().getDisplayName().isBlank()
                                ? e.getKey() : e.getValue().getDisplayName()))
                .toList();
    }

    @Override
    public boolean isEmailAllowed(String provider, String email) {
        Provider p = find(provider).orElse(null);
        if (p == null) return false;
        List<String> domains = p.getAllowedEmailDomains().stream()
                .map(d -> d.trim().toLowerCase(Locale.ROOT)).filter(d -> !d.isEmpty()).toList();
        if (domains.isEmpty()) return true;
        int at = email.lastIndexOf('@');
        return at > 0 && domains.contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
