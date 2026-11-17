package lms.sales.stock.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.StocktakeUseCase;
import lms.sales.stock.infrastructure.web.StockItemController.StockItemResponse;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Điều chỉnh tồn tay và kiểm kho (bắt buộc lý do). Khóa: adjust, stocktake. Trả mặt hàng sau thay đổi. */
@RestController
@RequestMapping("/internal/sales")
@RequiresModule(TenantModule.SALES)
public class StockAdjustmentController {

    public record AdjustRequest(@NotNull UUID titleId, @NotNull Integer delta, @NotBlank @Size(max = 500) String reason) {
    }

    public record StocktakeRequest(@NotNull UUID titleId, @NotNull Integer countedOnHand,
                                   @NotBlank @Size(max = 500) String reason) {
    }

    private final AdjustStockUseCase adjust;
    private final StocktakeUseCase stocktake;
    private final GetStockItemUseCase getItem;

    public StockAdjustmentController(AdjustStockUseCase adjust, StocktakeUseCase stocktake,
                                     GetStockItemUseCase getItem) {
        this.adjust = adjust;
        this.stocktake = stocktake;
        this.getItem = getItem;
    }

    @PostMapping("/stock-adjustments")
    public StockItemResponse adjust(@Valid @RequestBody AdjustRequest r, @AuthenticationPrincipal Jwt jwt) {
        UUID tenant = RequestSupport.tenant();
        adjust.execute(tenant, RequestSupport.user(jwt), r.titleId(), r.delta(), r.reason());
        return StockItemResponse.from(getItem.execute(tenant, r.titleId()));
    }

    @PostMapping("/stocktakes")
    public StockItemResponse stocktake(@Valid @RequestBody StocktakeRequest r, @AuthenticationPrincipal Jwt jwt) {
        UUID tenant = RequestSupport.tenant();
        stocktake.execute(tenant, RequestSupport.user(jwt), r.titleId(), r.countedOnHand(), r.reason());
        return StockItemResponse.from(getItem.execute(tenant, r.titleId()));
    }
}
