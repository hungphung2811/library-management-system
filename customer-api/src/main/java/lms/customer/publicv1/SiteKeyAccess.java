package lms.customer.publicv1;

import lms.customer.storefront.Feature;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;

/**
 * Phân giải {@code X-Site-Key} của Public API v1 thành storefront (tenant + Application) qua {@link StorefrontAccessCache}
 * (SaaS API Authentication P2, D4). Site key CHỈ chọn Application, không phải xác thực. Thiếu, sai định dạng, không tồn
 * tại, đã xoay vòng, Application tạm ngưng, tenant không khả dụng hoặc thiếu module: cùng {@code 404 SHOP_NOT_AVAILABLE}
 * (không phân biệt, không lộ tồn tại); identity lỗi thì 503 chung. Site key thô không bao giờ vào log hay khóa bộ đệm.
 */
public class SiteKeyAccess {

    public static final String HEADER = "X-Site-Key";

    private final StorefrontAccessCache storefronts;

    public SiteKeyAccess(StorefrontAccessCache storefronts) {
        this.storefronts = storefronts;
    }

    /** @param siteKey giá trị header {@link #HEADER}, có thể null (thiếu header). */
    public StorefrontAccess resolve(String siteKey) {
        return storefronts.resolveBySiteKey(siteKey);
    }

    /** Như {@link #resolve} và yêu cầu tenant có module cho {@code feature} (không đủ thì cũng 404 SHOP_NOT_AVAILABLE). */
    public StorefrontAccess resolve(String siteKey, Feature feature) {
        return resolve(siteKey).require(feature);
    }
}
