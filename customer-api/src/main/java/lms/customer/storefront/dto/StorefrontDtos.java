package lms.customer.storefront.dto;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class StorefrontDtos {

    private StorefrontDtos() {
    }

    /**
     * Kết quả phân giải storefront từ identity (ADR-0011 2.1). Nội bộ customer-api, KHÔNG bao giờ trả ra ngoài:
     * accessToken là token dịch vụ (claim svc). expiresIn tính bằng giây.
     */
    public record StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn,
                                   Set<String> allowedOrigins) {

        /**
         * {@code allowedOrigins} (ADR-0013 2.3) có thể vắng khi identity chưa nâng cấp: coi là rỗng. Mỗi origin được
         * hạ chữ thường để so khớp chính xác với {@code Origin} đã hạ chữ của trình duyệt.
         */
        public StorefrontAccess {
            if (allowedOrigins == null) {
                allowedOrigins = Set.of();
            } else {
                Set<String> normalized = new LinkedHashSet<>();
                for (String origin : allowedOrigins) {
                    if (origin != null && !origin.isBlank()) {
                        normalized.add(origin.trim().toLowerCase(Locale.ROOT));
                    }
                }
                allowedOrigins = Set.copyOf(normalized);
            }
        }

        public StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn) {
            this(tenantId, code, name, accessToken, expiresIn, null);
        }

        /** @param lowerCaseOrigin origin đã hạ chữ thường. */
        public boolean allowsOrigin(String lowerCaseOrigin) {
            return allowedOrigins.contains(lowerCaseOrigin);
        }

        @Override
        public String toString() {
            return "StorefrontAccess[code=" + code + "]";
        }
    }

    /** Thông tin công khai tối thiểu của tenant: GET /public/t/{code}. */
    public record ShopView(String code, String name) {
    }
}
