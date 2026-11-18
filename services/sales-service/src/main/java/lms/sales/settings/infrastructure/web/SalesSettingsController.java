package lms.sales.settings.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.time.Instant;
import lms.cache.RequestCache;
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

    /** Cache chỉ ở đường GET của controller; use case nghiệp vụ đọc cài đặt trực tiếp qua port, không qua cache. */
    static final String CACHE_NAMESPACE = "sales.settings";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final GetSalesSettingsUseCase getSettings;
    private final UpdateSalesSettingsUseCase updateSettings;
    private final RequestCache cache;

    public SalesSettingsController(GetSalesSettingsUseCase getSettings, UpdateSalesSettingsUseCase updateSettings,
                                   RequestCache cache) {
        this.getSettings = getSettings;
        this.updateSettings = updateSettings;
        this.cache = cache;
    }

    @GetMapping
    public SettingsResponse get() {
        var tenant = RequestSupport.tenant();
        return cache.get(CACHE_NAMESPACE, "current", CACHE_TTL, new TypeReference<SettingsResponse>() { },
                () -> SettingsResponse.from(getSettings.execute(tenant)));
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest r,
                                   @AuthenticationPrincipal Jwt jwt) {
        SettingsResponse saved = SettingsResponse.from(updateSettings.execute(new UpdateSalesSettingsCommand(
                RequestSupport.tenant(), RequestSupport.user(jwt), r.vatRateBps(), r.pricesIncludeVat(),
                r.returnWindowDays(), r.fixedShippingFee(), r.lowStockThreshold(), r.walkInPiiRetentionMonths(),
                r.version())));
        cache.evictNamespace(CACHE_NAMESPACE);
        return saved;
    }
}
