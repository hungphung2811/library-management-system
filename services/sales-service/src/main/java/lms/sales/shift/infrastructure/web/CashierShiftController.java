package lms.sales.shift.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftDetailView;
import lms.sales.shift.application.ShiftView;
import lms.sales.shift.domain.ShiftStatus;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ca thu ngân CỦA TÔI (ADR-0030). Thu ngân chỉ thao tác ca của chính mình (người dùng lấy từ token, ca của người khác
 * trả 404 SHIFT_NOT_FOUND). Khóa quyền (Phụ lục A): CashierShiftController/open, /current, /mine, /getMine, /close.
 * Quản lý xem và xử lý mọi ca ở {@link CashierShiftAdminController} (khóa riêng).
 */
@RestController
@RequestMapping("/internal/sales/shifts")
@RequiresModule(TenantModule.SALES)
public class CashierShiftController {

    public record OpenShiftRequest(@NotNull @Min(0) Long openingFloat, @Size(max = 100) String registerLabel,
                                   @NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record CloseShiftRequest(@NotNull @Min(0) Long countedCash, @Size(max = 500) String varianceReason) {
    }

    public record MyShiftsQuery(ShiftStatus status, Integer page, Integer size) {
    }

    private final CashierShiftUseCases shifts;

    public CashierShiftController(CashierShiftUseCases shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftView open(@Valid @RequestBody OpenShiftRequest body, @AuthenticationPrincipal Jwt jwt) {
        return shifts.open(RequestSupport.tenant(), RequestSupport.user(jwt),
                new CashierShiftUseCases.OpenCommand(body.openingFloat(), body.registerLabel(),
                        RequestSupport.clientRequestId(body.clientRequestId())));
    }

    @GetMapping("/me/current")
    public ShiftDetailView current(@AuthenticationPrincipal Jwt jwt) {
        return shifts.current(RequestSupport.tenant(), RequestSupport.user(jwt));
    }

    @GetMapping("/me")
    public PageResult<ShiftView> mine(MyShiftsQuery query, @AuthenticationPrincipal Jwt jwt) {
        return shifts.mine(RequestSupport.tenant(), RequestSupport.user(jwt), query.status(),
                RequestSupport.page(query.page()), RequestSupport.size(query.size()));
    }

    @GetMapping("/me/{id}")
    public ShiftDetailView getMine(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return shifts.getMine(RequestSupport.tenant(), RequestSupport.user(jwt), id);
    }

    @PostMapping("/me/current/closing")
    public ShiftView close(@Valid @RequestBody CloseShiftRequest body, @AuthenticationPrincipal Jwt jwt) {
        return shifts.close(RequestSupport.tenant(), RequestSupport.user(jwt),
                new CashierShiftUseCases.CloseCommand(body.countedCash(), body.varianceReason()));
    }
}
