package lms.sales.stock.infrastructure.web;

import java.util.List;
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

    /** {@code sort}: newest | price_asc | price_desc (ngoài allowlist: 400 INVALID_REQUEST); {@code inStock=true} lọc còn hàng; {@code group}: EDITION (mặc định) | WORK (gộp ấn bản cùng tác phẩm, ADR-0026). */
    public record StorefrontQuery(String keyword, String sort, Boolean inStock, String group, Integer page,
                                  Integer size) {
    }

    /** Gợi ý tìm kiếm: {@code q} 2..50 ký tự (use case kiểm lại), {@code limit} 1..8 (mặc định 5). */
    public record SuggestionQuery(String q, Integer limit) {
    }

    private final StorefrontQueryUseCases storefront;

    public StorefrontController(StorefrontQueryUseCases storefront) {
        this.storefront = storefront;
    }

    @GetMapping("/items")
    public PageResult<StorefrontQueryUseCases.ItemView> list(StorefrontQuery query) {
        int size = query.size() == null || query.size() < 1 ? StorefrontQueryUseCases.DEFAULT_PAGE_SIZE
                : Math.min(query.size(), StorefrontQueryUseCases.MAX_PAGE_SIZE);
        return storefront.list(RequestSupport.tenant(), query.keyword(), query.sort(), query.inStock(), query.group(),
                RequestSupport.page(query.page()), size);
    }

    @GetMapping("/suggestions")
    public List<StorefrontQueryUseCases.SuggestionView> suggestions(SuggestionQuery query) {
        return storefront.suggest(RequestSupport.tenant(), query.q(), query.limit());
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
