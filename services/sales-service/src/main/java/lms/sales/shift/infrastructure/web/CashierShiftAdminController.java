package lms.sales.shift.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.shift.application.CashierShiftAdminUseCases;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftDetailView;
import lms.sales.shift.application.ShiftView;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý ca thu ngân của MỌI thu ngân trong tenant (ADR-0030): tìm, xem, đóng thay, mở lại, duyệt chênh lệch. Tách
 * khỏi {@link CashierShiftController} vì ADR-0003 chỉ kiểm quyền theo endpoint. Khóa quyền (Phụ lục A):
 * CashierShiftAdminController/search, /get, /closeOnBehalf, /reopen, /approveVariance.
 */
@RestController
@RequestMapping("/internal/sales/shifts")
@RequiresModule(TenantModule.SALES)
public class CashierShiftAdminController {

    public record ShiftSearchQuery(UUID cashierUserId, ShiftStatus status, VarianceStatus varianceStatus,
                                   Instant from, Instant to, Integer page, Integer size) {
    }

    public record CloseShiftRequest(@NotNull @Min(0) Long countedCash, @Size(max = 500) String varianceReason) {
    }

    /** Lý do không đánh {@code @NotBlank} để thiếu lý do trả đúng mã REOPEN_REASON_REQUIRED của miền ca. */
    public record ReopenRequest(@Size(max = 500) String reason) {
    }

    public record ApproveVarianceRequest(@Size(max = 500) String note) {
    }

    private final CashierShiftAdminUseCases shifts;

    public CashierShiftAdminController(CashierShiftAdminUseCases shifts) {
        this.shifts = shifts;
    }

    @GetMapping
    public PageResult<ShiftView> search(ShiftSearchQuery query) {
        return shifts.search(RequestSupport.tenant(), new CashierShiftRepository.Criteria(query.cashierUserId(),
                query.status(), query.varianceStatus(), query.from(), query.to()), RequestSupport.page(query.page()),
                RequestSupport.size(query.size()));
    }

    @GetMapping("/{id}")
    public ShiftDetailView get(@PathVariable UUID id) {
        return shifts.get(RequestSupport.tenant(), id);
    }

    @PostMapping("/{id}/closing")
    public ShiftView closeOnBehalf(@PathVariable UUID id, @Valid @RequestBody CloseShiftRequest body,
                                   @AuthenticationPrincipal Jwt jwt) {
        return shifts.closeOnBehalf(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                new CashierShiftUseCases.CloseCommand(body.countedCash(), body.varianceReason()));
    }

    @PostMapping("/{id}/reopening")
    public ShiftView reopen(@PathVariable UUID id, @Valid @RequestBody ReopenRequest body,
                            @AuthenticationPrincipal Jwt jwt) {
        return shifts.reopen(RequestSupport.tenant(), RequestSupport.user(jwt), id, body.reason());
    }

    @PostMapping("/{id}/variance-approval")
    public ShiftView approveVariance(@PathVariable UUID id,
                                     @Valid @RequestBody(required = false) ApproveVarianceRequest body,
                                     @AuthenticationPrincipal Jwt jwt) {
        return shifts.approveVariance(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.note());
    }
}
