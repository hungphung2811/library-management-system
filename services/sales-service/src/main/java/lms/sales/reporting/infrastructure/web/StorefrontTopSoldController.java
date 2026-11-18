package lms.sales.reporting.infrastructure.web;

import java.util.List;
import lms.sales.reporting.application.StorefrontTopSoldUseCase;
import lms.sales.reporting.application.StorefrontTopSoldUseCase.TopSold;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.StorefrontAccessible;
import lms.security.permission.TenantModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Top bán chạy cho storefront (customer-api ghép tác giả nổi bật). Cùng đường dẫn gốc với StorefrontController nhưng
 * đặt ở context reporting vì reporting đã phụ thuộc stock (stock không được phụ thuộc ngược lại: vòng slice).
 * {@code @StorefrontAccessible}: token dịch vụ storefront vào được (kiểm tenant và module SALES, không kiểm khóa);
 * người dùng thường cần khóa StorefrontTopSoldController/topSold. KHÔNG dùng CurrentUser. Chỉ trả titleId, quantity.
 */
@RestController
@RequestMapping("/internal/sales/storefront")
@RequiresModule(TenantModule.SALES)
@StorefrontAccessible
public class StorefrontTopSoldController {

    /** {@code days} mặc định 90, chặn 1..365; {@code limit} mặc định 100, chặn 1..100. */
    public record TopSoldQuery(Integer days, Integer limit) {
    }

    private final StorefrontTopSoldUseCase topSold;

    public StorefrontTopSoldController(StorefrontTopSoldUseCase topSold) {
        this.topSold = topSold;
    }

    @GetMapping("/top-sold")
    public List<TopSold> topSold(TopSoldQuery query) {
        return topSold.execute(RequestSupport.tenant(), query.days(), query.limit());
    }
}
