package lms.sales.shift.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.shift.application.ShiftSettingsUseCases;
import lms.sales.shift.domain.ShiftSettings;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấu hình ca thu ngân của tenant: cờ chặn bán khi chưa mở ca (mặc định tắt) và ngưỡng chênh lệch (mặc định 0 đồng).
 * Khóa quyền (Phụ lục A): CashierShiftSettingsController/get, /update. Không cache (cờ phải có hiệu lực ngay).
 */
@RestController
@RequestMapping("/internal/sales/shift-settings")
@RequiresModule(TenantModule.SALES)
public class CashierShiftSettingsController {

    public record UpdateShiftSettingsRequest(@NotNull Boolean requireOpenShiftForPos,
                                             @NotNull @Min(0) Long varianceThreshold, @NotNull @Min(0) Long version) {
    }

    public record ShiftSettingsResponse(boolean requireOpenShiftForPos, long varianceThreshold, long version,
                                        Instant updatedAt) {
        static ShiftSettingsResponse from(ShiftSettings s) {
            return new ShiftSettingsResponse(s.requireOpenShiftForPos(), s.varianceThreshold(), s.version(),
                    s.updatedAt());
        }
    }

    private final ShiftSettingsUseCases settings;

    public CashierShiftSettingsController(ShiftSettingsUseCases settings) {
        this.settings = settings;
    }

    @GetMapping
    public ShiftSettingsResponse get() {
        return ShiftSettingsResponse.from(settings.get(RequestSupport.tenant()));
    }

    @PutMapping
    public ShiftSettingsResponse update(@Valid @RequestBody UpdateShiftSettingsRequest body,
                                        @AuthenticationPrincipal Jwt jwt) {
        return ShiftSettingsResponse.from(settings.update(RequestSupport.tenant(), RequestSupport.user(jwt),
                body.requireOpenShiftForPos(), body.varianceThreshold(), body.version()));
    }
}
