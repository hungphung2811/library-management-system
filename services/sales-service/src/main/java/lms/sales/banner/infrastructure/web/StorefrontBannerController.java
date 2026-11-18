package lms.sales.banner.infrastructure.web;

import java.util.List;
import lms.sales.banner.application.PublicBannerQuery;
import lms.sales.banner.application.PublicBannerView;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.StorefrontAccessible;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Banner đang hiệu lực cho storefront (ADR-0019 2.5, 2.8). Chỉ đọc, {@code @StorefrontAccessible}: token dịch vụ
 * storefront vào được (kiểm tenant và module SALES, không kiểm khóa). Tối đa 10 banner, đúng 5 trường.
 */
@RestController
@RequestMapping("/internal/sales/storefront/banners")
@RequiresModule(TenantModule.SALES)
@StorefrontAccessible
public class StorefrontBannerController {

    private final PublicBannerQuery query;

    public StorefrontBannerController(PublicBannerQuery query) {
        this.query = query;
    }

    @GetMapping
    public List<PublicBannerView> list() {
        return query.live(RequestSupport.tenant());
    }
}
