package lms.sales.stock.infrastructure.web;

import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.security.permission.RequiresModule;
import lms.security.permission.StorefrontAccessible;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh mục sách đang bán cho storefront (ADR-0011 mục 2.6, 2.12). Chỉ đọc, {@code @StorefrontAccessible}: token dịch
 * vụ storefront vào được (kiểm tenant và module SALES, không kiểm khóa); người dùng thường vẫn cần khóa
 * StorefrontController/*. KHÔNG dùng CurrentUser. Chỉ mặt hàng active; không trả onHand, reserved, available, giá nhập,
 * ngưỡng, tenant_id, ISBN. Trang tối đa 50.
 */
@RestController
@RequestMapping("/internal/sales/storefront")
@RequiresModule(TenantModule.SALES)
@StorefrontAccessible
public class StorefrontController {

    public record StorefrontQuery(String keyword, Integer page, Integer size) {
    }

    private final StorefrontQueryUseCases storefront;

    public StorefrontController(StorefrontQueryUseCases storefront) {
        this.storefront = storefront;
    }

    @GetMapping("/items")
    public PageResult<StorefrontQueryUseCases.ItemView> list(StorefrontQuery query) {
        int size = query.size() == null || query.size() < 1 ? StorefrontQueryUseCases.DEFAULT_PAGE_SIZE
                : Math.min(query.size(), StorefrontQueryUseCases.MAX_PAGE_SIZE);
        return storefront.list(RequestSupport.tenant(), query.keyword(), RequestSupport.page(query.page()), size);
    }

    @GetMapping("/items/{titleId}")
    public StorefrontQueryUseCases.ItemDetailView get(@PathVariable UUID titleId) {
        return storefront.get(RequestSupport.tenant(), titleId);
    }

    @GetMapping("/items/{titleId}/preview")
    public StorefrontQueryUseCases.PreviewView preview(@PathVariable UUID titleId) {
        return storefront.preview(RequestSupport.tenant(), titleId);
    }
}
