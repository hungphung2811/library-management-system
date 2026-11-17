package lms.identity.contract.infrastructure.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.identity.contract.application.ContractPaymentUseCase;
import lms.identity.contract.application.ContractRequests.PaymentRequest;
import lms.identity.contract.application.ContractRequests.ReverseRequest;
import lms.identity.contract.application.ContractResponses.PaymentResponse;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thanh toán thủ công của hợp đồng (ADR-0012). Tách controller riêng để sau này tách quyền xem/ghi tiền chỉ phải đổi
 * annotation ở đây. Chỉ có ghi và đảo toàn phần; KHÔNG có PUT/DELETE (thanh toán bất biến).
 */
@RestController
@RequestMapping("/api/platform/contracts/{id}/payments")
@PlatformAdminOnly
public class PlatformContractPaymentController {

    private final ContractPaymentUseCase payments;

    public PlatformContractPaymentController(ContractPaymentUseCase payments) {
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse record(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return payments.record(id, request, actor(jwt));
    }

    @PostMapping("/{paymentId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse reverse(@PathVariable UUID id, @PathVariable UUID paymentId,
                                   @Valid @RequestBody(required = false) ReverseRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return payments.reverse(id, paymentId, request, actor(jwt));
    }

    private static UUID actor(Jwt jwt) {
        return CurrentUser.from(jwt).userId();
    }
}
