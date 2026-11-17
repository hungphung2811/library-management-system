package lms.identity.contract.application;

import static lms.identity.contract.application.ContractTransitions.data;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.identity.contract.application.ContractRequests.BillingCycleRequest;
import lms.identity.contract.application.ContractRequests.CancelRequest;
import lms.identity.contract.application.ContractRequests.ContractRequest;
import lms.identity.contract.application.ContractRequests.ExtendRequest;
import lms.identity.contract.application.ContractRequests.RenewRequest;
import lms.identity.contract.application.ContractResponses.ContractResponse;
import lms.identity.contract.domain.BillingCycle;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractPaymentRepository;
import lms.identity.contract.domain.ContractPeriod;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import lms.identity.tenant.application.PlatformEvents;
import lms.identity.tenant.application.TenantModuleOperations.ContractSuspension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Các thao tác ghi hợp đồng của platform admin (ADR-0012 mục 2.3.2, 2.5.1): tạo, sửa, kích hoạt, kéo dài, hủy, gia hạn.
 * Mỗi thao tác chạy trong một transaction đã khóa dòng tenant, ghi lịch sử CÙNG transaction, phát event + log sau
 * commit. Chồng lấn: lớp ứng dụng (409 CONTRACT_OVERLAP), lưới an toàn là ràng buộc EXCLUDE trong DB.
 */
public class ManageContractUseCase {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final ContractRepository contracts;
    private final ContractPaymentRepository payments;
    private final ContractExecutor executor;
    private final ContractTransitions transitions;
    private final ContractPolicy policy;
    private final Clock clock;

    public ManageContractUseCase(ContractRepository contracts, ContractPaymentRepository payments,
                                 lms.identity.contract.application.ContractRuntime runtime) {
        this.contracts = contracts;
        this.payments = payments;
        this.executor = runtime.executor();
        this.transitions = runtime.transitions();
        this.policy = runtime.policy();
        this.clock = runtime.clock();
    }

    // ------------------------------------------------------------------ tạo / sửa

    public ContractResponse create(UUID tenantId, ContractRequest request, UUID actor) {
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = Contract.draft(tenantId, content(request), actor, now, policy.zone());
            c = contracts.save(c);
            transitions.log(actor, c, "CREATED", now, data("name", c.name(), "modules", moduleNames(c.modules()),
                    "startsAt", c.startsAt().toString(), "endsAt", c.endsAt().toString(),
                    "totalAmount", c.totalAmount()));
            buf.add(PlatformEvents.operation("CONTRACT_CREATED", actor, tenantId, c.id(),
                    "contract=" + c.id() + ", status=DRAFT, modules=" + moduleNames(c.modules())));
            AUDIT.info("platform-audit operation=contract-created actor={} tenant={} contract={}", actor, tenantId,
                    c.id());
            return response(c, now);
        });
    }

    public ContractResponse update(UUID contractId, ContractRequest request, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = load(contractId);
            checkVersion(c, request.version());
            Map<String, Object[]> changes = c.update(content(request), actor, now, policy.zone());
            if (!changes.isEmpty()) {
                c = contracts.save(c);
                Map<String, Object> diff = new LinkedHashMap<>();
                changes.forEach((field, ba) -> diff.put(field, data("from", String.valueOf(ba[0]), "to",
                        String.valueOf(ba[1]))));
                transitions.log(actor, c, "UPDATED", now, diff);
                buf.add(PlatformEvents.operation("CONTRACT_UPDATED", actor, tenantId, c.id(),
                        "contract=" + c.id() + ", fields=" + changes.keySet()));
            }
            return response(c, now);
        });
    }

    // ------------------------------------------------------------------ kích hoạt

    /** DRAFT → ACTIVE; module áp dụng ngay nếu {@code starts_at <= now}, ngược lại job áp dụng khi tới ngày. */
    public ContractResponse activate(UUID contractId, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = load(contractId);
            c.activate(actor, now);
            transitions.resolveOverlapsForActivation(c, actor, now, buf);
            c = contracts.save(c);
            transitions.log(actor, c, "ACTIVATED", now, data("startsAt", c.startsAt().toString(),
                    "endsAt", String.valueOf(c.endsAt())));
            buf.add(PlatformEvents.operation("CONTRACT_ACTIVATED", actor, tenantId, c.id(),
                    "contract=" + c.id() + ", status=ACTIVE"));
            AUDIT.info("platform-audit operation=contract-activated actor={} tenant={} contract={}", actor, tenantId,
                    c.id());
            if (!c.startsAt().isAfter(now)) {
                transitions.applyModules(c, actor, now, buf);
                c = load(contractId);
            }
            return response(c, now);
        });
    }

    // ------------------------------------------------------------------ kéo dài / hủy / gia hạn

    public ContractResponse extend(UUID contractId, ExtendRequest request, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = load(contractId);
            checkVersion(c, request.version());
            LocalDate oldEnd = ContractPeriod.endDate(c.endsAt(), policy.zone());
            c.extend(request.endDate(), actor, now, policy.zone());
            transitions.requireNoOverlap(c);
            c = contracts.save(c);
            transitions.log(actor, c, "EXTENDED", now, data("oldEndDate", String.valueOf(oldEnd),
                    "newEndDate", request.endDate().toString()));
            buf.add(PlatformEvents.operation("CONTRACT_EXTENDED", actor, tenantId, c.id(),
                    "contract=" + c.id() + ", endDate=" + request.endDate()));
            return response(c, now);
        });
    }

    /**
     * Hủy hợp đồng DRAFT/ACTIVE (lý do bắt buộc). ACTIVE đã áp dụng module: thu hồi module ngay, không ân hạn; nếu
     * tenant sẽ không còn module nào thì giữ một module và suspend với nguyên nhân CONTRACT_CANCELLED (KHÔNG tự
     * kích hoạt lại khi có hợp đồng mới: platform admin phải activate tay).
     */
    public ContractResponse cancel(UUID contractId, CancelRequest request, UUID actor) {
        UUID tenantId = tenantOf(contractId);
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = load(contractId);
            boolean revoke = c.status() == ContractStatus.ACTIVE && c.modulesApplied();
            c.cancel(request == null ? null : request.reason(), actor, now);
            ContractTransitions.Revoked revoked = revoke
                    ? transitions.revokeModules(c, ContractSuspension.CANCELLED, actor, now, buf)
                    : new ContractTransitions.Revoked(java.util.List.of(), null, false);
            c = contracts.save(c);
            transitions.log(actor, c, "CANCELLED", now, data("reason", c.cancelReason(),
                    "modulesDisabled", revoked.disabled(), "retainedModule", revoked.retained(),
                    "tenantSuspended", revoked.suspended()));
            // detail không có lý do hủy (envelope rơi vào audit của tenant)
            buf.add(PlatformEvents.operation("CONTRACT_CANCELLED", actor, tenantId, c.id(),
                    "contract=" + c.id() + ", status=CANCELLED, disabled=" + revoked.disabled() + ", suspended="
                            + revoked.suspended()));
            AUDIT.info("platform-audit operation=contract-cancelled actor={} tenant={} contract={} disabled={} suspended={}",
                    actor, tenantId, c.id(), revoked.disabled(), revoked.suspended());
            return response(c, now);
        });
    }

    /** Tạo NHÁP mới từ hợp đồng ACTIVE/EXPIRED: module chép từ nguồn, không tự kích hoạt. */
    public ContractResponse renew(UUID sourceId, RenewRequest request, UUID actor) {
        UUID tenantId = tenantOf(sourceId);
        Instant now = now();
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract source = load(sourceId);
            if (source.status() != ContractStatus.ACTIVE && source.status() != ContractStatus.EXPIRED) {
                throw new ContractException(Reason.CONTRACT_NOT_EDITABLE,
                        "Chỉ gia hạn từ hợp đồng ACTIVE hoặc EXPIRED, hiện là " + source.status());
            }
            LocalDate start = request.startDate();
            if (start == null) {
                LocalDate sourceEnd = ContractPeriod.endDate(source.endsAt(), policy.zone());
                if (sourceEnd == null) {
                    throw new ContractException(Reason.INVALID_CONTRACT,
                            "Hợp đồng chuyển tiếp không có ngày kết thúc: phải nhập startDate");
                }
                start = sourceEnd.plusDays(1);
            }
            Contract.Content content = new Contract.Content(
                    request.name() == null || request.name().isBlank() ? source.name() : request.name(),
                    source.modules(), start, request.endDate(), request.totalAmount(), cycle(request.billingCycle()),
                    request.internalNote());
            Contract c = contracts.save(Contract.draft(tenantId, content, actor, now, policy.zone()));
            transitions.log(actor, c, "CREATED", now, data("renewedFrom", source.id().toString(),
                    "modules", moduleNames(c.modules()), "startsAt", c.startsAt().toString(),
                    "endsAt", c.endsAt().toString(), "totalAmount", c.totalAmount()));
            buf.add(PlatformEvents.operation("CONTRACT_CREATED", actor, tenantId, c.id(),
                    "contract=" + c.id() + ", status=DRAFT, renewedFrom=" + source.id()));
            return response(c, now);
        });
    }

    // ------------------------------------------------------------------ nội bộ

    private UUID tenantOf(UUID contractId) {
        return contracts.findById(contractId).orElseThrow(() -> notFound(contractId)).tenantId();
    }

    private Contract load(UUID contractId) {
        return contracts.findById(contractId).orElseThrow(() -> notFound(contractId));
    }

    static ContractException notFound(UUID id) {
        return new ContractException(Reason.CONTRACT_NOT_FOUND, "Không tìm thấy hợp đồng " + id);
    }

    private static void checkVersion(Contract c, Long version) {
        if (version == null) throw new ContractException(Reason.INVALID_CONTRACT, "Thiếu version của hợp đồng");
        if (version != c.version()) {
            throw new ContractException(Reason.CONTRACT_VERSION_CONFLICT,
                    "Hợp đồng đã bị người khác thay đổi (version hiện tại " + c.version() + "), hãy tải lại");
        }
    }

    private ContractResponse response(Contract c, Instant now) {
        long paid = payments.paidByContracts(java.util.List.of(c.id())).getOrDefault(c.id(), 0L);
        return ContractResponses.platform(c, paid, policy, now);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static Contract.Content content(ContractRequest r) {
        Set<ContractModule> modules = EnumSet.noneOf(ContractModule.class);
        if (r.modules() != null) r.modules().forEach(m -> modules.add(ContractModule.parse(m)));
        return new Contract.Content(r.name(), modules, r.startDate(), r.endDate(), r.totalAmount(),
                cycle(r.billingCycle()), r.internalNote());
    }

    private static BillingCycle cycle(BillingCycleRequest r) {
        return r == null ? null : BillingCycle.ofNullable(r.unit(), r.value());
    }

    private static java.util.List<String> moduleNames(Set<ContractModule> modules) {
        return modules.stream().sorted().map(ContractModule::name).toList();
    }
}
