package lms.identity.contract.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractHistoryEntry;
import lms.identity.contract.domain.ContractHistoryRepository;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ModuleRevocationPlan;
import lms.identity.tenant.application.PlatformEvents;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.TenantModuleOperations.ContractSuspension;
import lms.identity.tenant.application.TenantModuleOperations.ModuleChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Các điểm chuyển của hợp đồng tác động vào module/tenant (ADR-0012 mục 2.3): áp dụng module, thu hồi module (hết hạn /
 * hủy), cắt hợp đồng legacy. Mọi phương thức chạy TRONG một transaction đã đặt {@code TenantContext} và đã khóa dòng
 * tenant; event gom vào {@link EventBuffer} để người gọi phát sau commit. Dùng chung cho API platform và job.
 */
final class ContractTransitions {

    private static final Logger AUDIT = LoggerFactory.getLogger("platform-audit");

    private final TenantModuleOperations ops;
    private final ContractRepository contracts;
    private final ContractHistoryRepository history;
    private final ContractPolicy policy;

    ContractTransitions(TenantModuleOperations ops, ContractRepository contracts, ContractHistoryRepository history,
                        ContractPolicy policy) {
        this.ops = ops;
        this.contracts = contracts;
        this.history = history;
        this.policy = policy;
    }

    ContractHistoryEntry log(UUID actor, Contract c, String type, Instant now, Map<String, Object> data) {
        ContractHistoryEntry entry = ContractHistoryEntry.by(actor, c.tenantId(), c.id(), type, now, data);
        history.append(entry);
        return entry;
    }

    static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ kích hoạt: cắt legacy + chồng lấn

    /**
     * Kiểm chồng lấn của {@code candidate} (sắp ACTIVE) với các hợp đồng ACTIVE khác của tenant: hợp đồng legacy chồng
     * lấn bị cắt tại ngày bắt đầu của {@code candidate}; hợp đồng khác chồng lấn → CONTRACT_OVERLAP.
     */
    void resolveOverlapsForActivation(Contract candidate, UUID actor, Instant now, EventBuffer buf) {
        for (Contract other : contracts.findActiveByTenant(candidate.tenantId())) {
            if (other.id().equals(candidate.id()) || !other.overlaps(candidate)) continue;
            if (!other.legacy()) {
                throw new ContractException(Reason.CONTRACT_OVERLAP,
                        "Chồng lấn với hợp đồng đang hiệu lực \"" + other.name() + "\" (" + other.id() + ")");
            }
            Instant oldEnd = other.endsAt();
            other.cutLegacyAt(candidate.startsAt(), actor, now);
            contracts.save(other);
            log(actor, other, "LEGACY_SUPERSEDED", now,
                    data("supersededBy", candidate.id().toString(), "oldEndsAt", String.valueOf(oldEnd),
                            "newEndsAt", other.endsAt().toString()));
            buf.add(PlatformEvents.operation("CONTRACT_UPDATED", actor, candidate.tenantId(), other.id(),
                    "contract=" + other.id() + ", legacySupersededBy=" + candidate.id()));
        }
    }

    /** Kéo dài: không được chồng hợp đồng ACTIVE khác (kể cả legacy). */
    void requireNoOverlap(Contract extended) {
        for (Contract other : contracts.findActiveByTenant(extended.tenantId())) {
            if (!other.id().equals(extended.id()) && other.overlaps(extended)) {
                throw new ContractException(Reason.CONTRACT_OVERLAP,
                        "Chồng lấn với hợp đồng đang hiệu lực \"" + other.name() + "\" (" + other.id() + ")");
            }
        }
    }

    // ------------------------------------------------------------------ áp dụng module

    /** Kết quả áp dụng module (cho test/log). */
    record Applied(List<String> enabled, boolean tenantReactivated) {
    }

    /**
     * Áp dụng module của hợp đồng ACTIVE khi tới starts_at: bật từng module (idempotent, chép role mẫu), dọn module giữ
     * lại của hợp đồng cũ, tự kích hoạt lại tenant nếu đang SUSPENDED do HẾT HẠN, đánh dấu đã áp dụng.
     */
    Applied applyModules(Contract c, UUID actor, Instant now, EventBuffer buf) {
        UUID tenantId = c.tenantId();
        List<String> enabled = new ArrayList<>();
        for (ContractModule m : c.modules().stream().sorted().toList()) {
            Optional<ModuleChange> change = ops.enable(tenantId, m.name(), actor, now);
            if (change.isPresent()) {
                enabled.add(m.name());
                buf.add(PlatformEvents.moduleEnabled(tenantId, m.name(), actor));
                buf.add(PlatformEvents.operation("TENANT_MODULE_ENABLED", actor, tenantId, tenantId,
                        "module=" + m.name() + ", rolesCreated=" + change.get().rolesCreated() + ", cause=contract:"
                                + c.id()));
            }
        }
        List<String> cleaned = cleanRetained(c, actor, now, buf);
        boolean reactivated = ops.activateIfSuspendedByExpiry(tenantId);
        if (reactivated) {
            buf.add(PlatformEvents.tenantStatusChanged(tenantId, true));
            buf.add(PlatformEvents.operation("TENANT_ACTIVATED", actor, tenantId, tenantId,
                    "status=ACTIVE, cause=contract:" + c.id()));
            AUDIT.info("platform-audit operation=tenant-reactivated-by-contract tenant={} contract={}", tenantId,
                    c.id());
        }
        c.markModulesApplied(now);
        contracts.save(c);
        log(actor, c, "MODULES_APPLIED", now, data("modulesEnabled", enabled, "retainedModulesReleased", cleaned,
                "tenantReactivated", reactivated));
        buf.add(PlatformEvents.operation("CONTRACT_MODULES_APPLIED", actor, tenantId, c.id(),
                "contract=" + c.id() + ", enabled=" + enabled + ", tenantReactivated=" + reactivated));
        AUDIT.info("platform-audit operation=contract-modules-applied tenant={} contract={} enabled={} reactivated={}",
                tenantId, c.id(), enabled, reactivated);
        return new Applied(enabled, reactivated);
    }

    /** Tắt module "giữ lại cho đủ bất biến" của hợp đồng cũ nếu không còn được hợp đồng nào bao phủ. */
    private List<String> cleanRetained(Contract applying, UUID actor, Instant now, EventBuffer buf) {
        List<String> released = new ArrayList<>();
        List<ContractModule> covered = coveredBy(applying.tenantId(), null, now, applying);
        for (Contract old : contracts.findWithRetainedModule(applying.tenantId())) {
            ContractModule retained = old.retainedModule();
            if (!covered.contains(retained) && ops.enabledModules(applying.tenantId()).contains(retained.name())) {
                Optional<ModuleChange> change = ops.disable(applying.tenantId(), retained.name(), actor, now);
                if (change.isPresent()) {
                    released.add(retained.name());
                    buf.add(PlatformEvents.moduleDisabled(applying.tenantId(), retained.name(), actor));
                    buf.add(PlatformEvents.operation("TENANT_MODULE_DISABLED", actor, applying.tenantId(),
                            applying.tenantId(), "module=" + retained.name() + ", cause=contract:" + applying.id()));
                }
            }
            old.clearRetained();
            contracts.save(old);
        }
        return released;
    }

    /** Module được các hợp đồng ACTIVE (trừ {@code except}) bao phủ ở {@code now}, cộng thêm {@code extra} nếu có. */
    private List<ContractModule> coveredBy(UUID tenantId, UUID except, Instant now, Contract extra) {
        List<ContractModule> covered = new ArrayList<>();
        for (Contract other : contracts.findActiveByTenant(tenantId)) {
            if (other.id().equals(except)) continue;
            if (other.covers(now, policy.gracePeriod())) covered.addAll(other.modules());
        }
        if (extra != null && extra.covers(now, policy.gracePeriod())) covered.addAll(extra.modules());
        return covered;
    }

    // ------------------------------------------------------------------ thu hồi module

    /** Kết quả thu hồi. */
    record Revoked(List<String> disabled, String retained, boolean suspended) {
    }

    /**
     * Thu hồi module của {@code c} (đã áp dụng) khi hết hạn/hủy: tắt module không được hợp đồng khác bao phủ, giữ lại
     * MỘT module nếu sẽ không còn module nào rồi suspend tenant (nguyên nhân theo {@code kind}). Không bao giờ đi
     * đường 409 TENANT_MODULE_REQUIRED.
     */
    Revoked revokeModules(Contract c, ContractSuspension kind, UUID actor, Instant now, EventBuffer buf) {
        UUID tenantId = c.tenantId();
        List<ContractModule> cover = coveredBy(tenantId, c.id(), now, null);
        List<ContractModule> enabledNow = ops.enabledModules(tenantId).stream().map(ContractModule::parse).toList();
        ModuleRevocationPlan plan = ModuleRevocationPlan.plan(c.modules(), cover, enabledNow);
        List<String> disabled = new ArrayList<>();
        for (ContractModule m : plan.toDisable()) {
            Optional<ModuleChange> change = ops.disable(tenantId, m.name(), actor, now);
            if (change.isPresent()) {
                disabled.add(m.name());
                buf.add(PlatformEvents.moduleDisabled(tenantId, m.name(), actor));
                buf.add(PlatformEvents.operation("TENANT_MODULE_DISABLED", actor, tenantId, tenantId,
                        "module=" + m.name() + ", cause=contract:" + c.id()));
            }
        }
        boolean suspended = false;
        if (plan.suspend()) {
            c.retain(plan.retained());
            suspended = ops.suspendByContract(tenantId, kind, c.id());
            if (suspended) {
                buf.add(PlatformEvents.tenantStatusChanged(tenantId, false));
                buf.add(PlatformEvents.operation("TENANT_SUSPENDED", actor, tenantId, tenantId,
                        "status=SUSPENDED, cause=" + (kind == ContractSuspension.EXPIRED ? "CONTRACT_EXPIRED"
                                : "CONTRACT_CANCELLED") + ", contract=" + c.id()));
                AUDIT.info("platform-audit operation=tenant-suspended-by-contract tenant={} contract={} cause={}",
                        tenantId, c.id(), kind);
            }
        }
        return new Revoked(disabled, plan.retained() == null ? null : plan.retained().name(), suspended);
    }
}
