package lms.sales.settings.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.SalesSettingsView;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cấu hình bán hàng theo tenant. Khóa quyền: SalesSettingsController/get, SalesSettingsController/update. */
@RestController
@RequestMapping("/internal/sales/settings")
@RequiresModule(TenantModule.SALES)
public class SalesSettingsController {

    public record UpdateSettingsRequest(@NotNull Integer vatRateBps, @NotNull Boolean pricesIncludeVat,
                                        @NotNull Integer returnWindowDays, @NotNull Long fixedShippingFee,
                                        @NotNull Integer lowStockThreshold, @NotNull Integer walkInPiiRetentionMonths,
                                        @NotNull Long version) {
    }

    public record SettingsResponse(int vatRateBps, boolean pricesIncludeVat, int returnWindowDays,
                                   long fixedShippingFee, int lowStockThreshold, int walkInPiiRetentionMonths,
                                   String currency, long version, Instant updatedAt) {
        static SettingsResponse from(SalesSettingsView v) {
            return new SettingsResponse(v.vatRateBps(), v.pricesIncludeVat(), v.returnWindowDays(),
                    v.fixedShippingFee(), v.lowStockThreshold(), v.walkInPiiRetentionMonths(), v.currency(),
                    v.version(), v.updatedAt());
        }
    }

    private final GetSalesSettingsUseCase getSettings;
    private final UpdateSalesSettingsUseCase updateSettings;

    public SalesSettingsController(GetSalesSettingsUseCase getSettings, UpdateSalesSettingsUseCase updateSettings) {
        this.getSettings = getSettings;
        this.updateSettings = updateSettings;
    }

    @GetMapping
    public SettingsResponse get() {
        return SettingsResponse.from(getSettings.execute(RequestSupport.tenant()));
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest r,
                                   @AuthenticationPrincipal Jwt jwt) {
        return SettingsResponse.from(updateSettings.execute(new UpdateSalesSettingsCommand(
                RequestSupport.tenant(), RequestSupport.user(jwt), r.vatRateBps(), r.pricesIncludeVat(),
                r.returnWindowDays(), r.fixedShippingFee(), r.lowStockThreshold(), r.walkInPiiRetentionMonths(),
                r.version())));
    }
}
