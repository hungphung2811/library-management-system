package lms.identity.tenant.application;

/**
 * Cấu hình quy tắc origin storefront (ADR-0013): số origin tối đa mỗi tenant (đếm cả dòng tắt) và cho phép {@code http}
 * ngoài localhost (staging không TLS).
 */
public record StorefrontOriginPolicy(int maxOriginsPerTenant, boolean allowHttp) {

    public static final int DEFAULT_MAX = 10;

    public StorefrontOriginPolicy {
        if (maxOriginsPerTenant < 1) throw new IllegalArgumentException("max-origins-per-tenant phải >= 1");
    }

    public static StorefrontOriginPolicy defaults() {
        return new StorefrontOriginPolicy(DEFAULT_MAX, false);
    }
}
