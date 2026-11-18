package lms.customer.storefront.dto;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.customer.storefront.Feature;
import lms.customer.web.PublicApiException;

public final class StorefrontDtos {

    private StorefrontDtos() {
    }

    /**
     * Kết quả phân giải storefront từ identity (ADR-0011 2.1). Nội bộ customer-api, KHÔNG bao giờ trả ra ngoài:
     * accessToken là token dịch vụ (claim svc). expiresIn tính bằng giây. {@code applicationId}: id Application khi phân
     * giải theo site key (Public Site Key, P2); null khi phân giải theo mã tenant ({code}) hoặc identity chưa nâng cấp.
     */
    public record StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn,
                                   Set<String> allowedOrigins, Set<String> modules, UUID applicationId) {

        /** Identity cũ chỉ trả tenant có SALES và không có trường modules: coi như chỉ SALES (ADR-0023 2.1). */
        static final Set<String> LEGACY_MODULES = Set.of("SALES");

        /**
         * {@code allowedOrigins} (ADR-0013 2.3) có thể vắng khi identity chưa nâng cấp: coi là rỗng. Mỗi origin được
         * hạ chữ thường để so khớp chính xác với {@code Origin} đã hạ chữ của trình duyệt.
         * {@code modules} vắng (null) là identity cũ: chỉ SALES; có thì chuẩn hóa viết hoa, bỏ mục rỗng.
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
            if (modules == null) {
                modules = LEGACY_MODULES;
            } else {
                Set<String> upper = new LinkedHashSet<>();
                for (String module : modules) {
                    if (module != null && !module.isBlank()) {
                        upper.add(module.trim().toUpperCase(Locale.ROOT));
                    }
                }
                modules = Set.copyOf(upper);
            }
        }

        public StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn,
                                Set<String> allowedOrigins, Set<String> modules) {
            this(tenantId, code, name, accessToken, expiresIn, allowedOrigins, modules, null);
        }

        public StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn) {
            this(tenantId, code, name, accessToken, expiresIn, null, null, null);
        }

        public StorefrontAccess(UUID tenantId, String code, String name, String accessToken, long expiresIn,
                                Set<String> allowedOrigins) {
            this(tenantId, code, name, accessToken, expiresIn, allowedOrigins, null, null);
        }

        /** Tenant có ít nhất một module đáp ứng tính năng {@code feature}. */
        public boolean supports(Feature feature) {
            for (String module : feature.modules()) {
                if (modules.contains(module)) {
                    return true;
                }
            }
            return false;
        }

        public boolean shopEnabled() {
            return supports(Feature.SHOP);
        }

        public boolean libraryEnabled() {
            return supports(Feature.LIBRARY);
        }

        /**
         * Không đủ module thì {@code 404 SHOP_NOT_AVAILABLE} cùng một thân với mọi lý do không khả dụng khác (không mã
         * mới, không lộ hợp đồng thuê); gọi TRƯỚC khi gọi bất kỳ service phía sau.
         */
        public StorefrontAccess require(Feature feature) {
            if (!supports(feature)) {
                throw PublicApiException.shopNotAvailable();
            }
            return this;
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

    /**
     * Thông tin công khai tối thiểu của tenant: GET /public/t/{code}. Hai cờ cho FE biết hiện phần nào (bán hàng, thư
     * viện); không trả danh sách module thô.
     */
    public record ShopView(String code, String name, boolean shopEnabled, boolean libraryEnabled) {
    }
}
