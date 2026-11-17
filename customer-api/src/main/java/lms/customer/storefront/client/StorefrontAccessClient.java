package lms.customer.storefront.client;

import java.util.Optional;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;

/** Cổng sang identity: đổi mã tenant lấy token dịch vụ storefront (tenant ACTIVE và bật SALES). */
public interface StorefrontAccessClient {

    /**
     * @return rỗng khi storefront không khả dụng (identity trả 404 SHOP_NOT_AVAILABLE); các lỗi khác (identity chết,
     *         bí mật sai) ném PublicApiException 503, không bao giờ bị đệm.
     */
    Optional<StorefrontAccess> issue(String tenantCode);
}
