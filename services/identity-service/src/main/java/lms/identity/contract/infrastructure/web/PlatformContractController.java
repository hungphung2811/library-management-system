package lms.identity.contract.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractRequests.CancelRequest;
import lms.identity.contract.application.ContractRequests.ContractRequest;
import lms.identity.contract.application.ContractRequests.ExtendRequest;
import lms.identity.contract.application.ContractRequests.RenewRequest;
import lms.identity.contract.application.ContractResponses.ContractDetailResponse;
import lms.identity.contract.application.ContractResponses.ContractResponse;
import lms.identity.contract.application.ContractResponses.ExpiringContractResponse;
import lms.identity.contract.application.ContractResponses.HistoryEntryResponse;
import lms.identity.contract.application.ContractResponses.TenantContractsResponse;
import lms.identity.contract.application.ManageContractUseCase;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hợp đồng thuê dịch vụ cho platform admin (ADR-0012, claim {@code platformAdmin=true}; không dùng khóa
 * Controller/method). Tất cả hoặc không: mọi platform admin xem được giá, thanh toán, ghi chú nội bộ. lms-api chuyển
 * nguyên {@code /api/platform/**}. Không có DELETE: hủy dùng {@code POST .../cancel}.
 */
@RestController
@RequestMapping("/api/platform")
@PlatformAdminOnly
public class PlatformContractController {

    private final ManageContractUseCase manage;
    private final ContractQueryUseCase query;

    public PlatformContractController(ManageContractUseCase manage, ContractQueryUseCase query) {
        this.manage = manage;
        this.query = query;
    }

    @PostMapping("/tenants/{tenantId}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractResponse create(@PathVariable UUID tenantId, @Valid @RequestBody ContractRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return manage.create(tenantId, request, actor(jwt));
    }

    @GetMapping("/tenants/{tenantId}/contracts")
    public TenantContractsResponse listByTenant(@PathVariable UUID tenantId) {
        return query.listByTenant(tenantId);
    }

    /** Khai báo trước {@code /contracts/{id}}; chỉ một tham số query nên không cần record. */
    @GetMapping("/contracts/expiring")
    public List<ExpiringContractResponse> expiring(@RequestParam(required = false) Integer withinDays) {
        return query.expiring(withinDays);
    }

    @GetMapping("/contracts/{id}")
    public ContractDetailResponse get(@PathVariable UUID id) {
        return query.get(id);
    }

    @PutMapping("/contracts/{id}")
    public ContractResponse update(@PathVariable UUID id, @Valid @RequestBody ContractRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return manage.update(id, request, actor(jwt));
    }

    @PostMapping("/contracts/{id}/activate")
    public ContractResponse activate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return manage.activate(id, actor(jwt));
    }

    @PostMapping("/contracts/{id}/extend")
    public ContractResponse extend(@PathVariable UUID id, @Valid @RequestBody ExtendRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return manage.extend(id, request, actor(jwt));
    }

    @PostMapping("/contracts/{id}/cancel")
    public ContractResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return manage.cancel(id, request, actor(jwt));
    }

    @PostMapping("/contracts/{id}/renew")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractResponse renew(@PathVariable UUID id, @Valid @RequestBody RenewRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return manage.renew(id, request, actor(jwt));
    }

    @GetMapping("/contracts/{id}/history")
    public List<HistoryEntryResponse> history(@PathVariable UUID id) {
        return query.history(id);
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
