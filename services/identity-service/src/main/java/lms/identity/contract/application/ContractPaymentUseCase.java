package lms.identity.contract.application;

import static lms.identity.contract.application.ContractTransitions.data;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.identity.contract.application.ContractRequests.PaymentRequest;
import lms.identity.contract.application.ContractRequests.ReverseRequest;
import lms.identity.contract.application.ContractResponses.PaymentResponse;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractPayment;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import lms.identity.tenant.application.PlatformEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ghi thanh toán thủ công và đảo toàn phần (ADR-0012 mục 2.2). Dòng thanh toán BẤT BIẾN: không sửa/xóa, sửa sai = đảo
 * toàn phần + ghi dòng đúng; mỗi dòng gốc đảo một lần. Ghi được cho ACTIVE/EXPIRED/CANCELLED, không cho DRAFT.
 */
public class ContractPaymentUseCase {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final ContractRepository contracts;
    private final ContractPaymentRepository payments;
    private final ContractExecutor executor;
    private final ContractTransitions transitions;
    private final ContractPolicy policy;
    private final Clock clock;

    public ContractPaymentUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                  ContractRuntime runtime) {
        this.contracts = contracts;
        this.payments = payments;
        this.executor = runtime.executor();
        this.transitions = runtime.transitions();
        this.policy = runtime.policy();
        this.clock = runtime.clock();
    }

    public PaymentResponse record(UUID contractId, PaymentRequest request, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = clock.instant();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = contracts.findById(contractId).orElseThrow(() -> ManageContractUseCase.notFound(contractId));
            if (c.status() == ContractStatus.DRAFT) {
                throw new ContractException(Reason.PAYMENT_NOT_ALLOWED, "Không ghi thanh toán cho hợp đồng NHÁP");
            }
            ContractPayment p = ContractPayment.record(contractId, tenantId, request.paidOn(),
                    request.amount() == null ? 0 : request.amount(), request.reference(), request.note(), actor, now);
            payments.append(p);
            // lịch sử không chứa mã tham chiếu/ghi chú; số tiền thì có (chỉ platform admin đọc lịch sử)
            transitions.log(actor, c, "PAYMENT_RECORDED", now, data("paymentId", p.id().toString(),
                    "amount", p.amount(), "paidOn", p.paidOn().toString()));
            buf.add(PlatformEvents.operation("CONTRACT_PAYMENT_RECORDED", actor, tenantId, contractId,
                    "contract=" + contractId + ", payment=" + p.id()));
            AUDIT.info("platform-audit operation=contract-payment-recorded actor={} tenant={} contract={} payment={}",
                    actor, tenantId, contractId, p.id());
            return PaymentResponse.of(p, null);
        });
    }

    public PaymentResponse reverse(UUID contractId, UUID paymentId, ReverseRequest request, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(policy.zone()).toLocalDate();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = contracts.findById(contractId).orElseThrow(() -> ManageContractUseCase.notFound(contractId));
            ContractPayment original = payments.findById(paymentId)
                    .filter(p -> p.contractId().equals(contractId))
                    .orElseThrow(() -> new ContractException(Reason.PAYMENT_NOT_FOUND,
                            "Không tìm thấy khoản thanh toán " + paymentId + " của hợp đồng " + contractId));
            ContractPayment reversal = ContractPayment.reversalOf(original, payments.isReversed(paymentId), today,
                    request == null ? null : request.note(), actor, now);
            payments.append(reversal);
            transitions.log(actor, c, "PAYMENT_REVERSED", now, data("paymentId", reversal.id().toString(),
                    "reverses", original.id().toString(), "amount", reversal.amount()));
            buf.add(PlatformEvents.operation("CONTRACT_PAYMENT_REVERSED", actor, tenantId, contractId,
                    "contract=" + contractId + ", payment=" + reversal.id() + ", reverses=" + original.id()));
            AUDIT.info("platform-audit operation=contract-payment-reversed actor={} tenant={} contract={} payment={}",
                    actor, tenantId, contractId, reversal.id());
            return PaymentResponse.of(reversal, null);
        });
    }

    private UUID tenantOf(UUID contractId) {
        return contracts.findById(contractId).orElseThrow(() -> ManageContractUseCase.notFound(contractId))
                .tenantId();
    }
}
