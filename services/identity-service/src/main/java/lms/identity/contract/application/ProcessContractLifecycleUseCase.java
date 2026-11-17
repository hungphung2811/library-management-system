package lms.identity.contract.application;

import static lms.identity.contract.application.ContractTransitions.data;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractPeriod;
import lms.identity.contract.domain.ContractReminderRepository;
import lms.identity.contract.domain.ContractRepository;
import lms.identity.contract.domain.ContractStatus;
import lms.identity.contract.domain.ReminderSchedule;
import lms.identity.contract.domain.TenantContractExpiringSoonEvent;
import lms.identity.tenant.application.ListActiveTenantAdminsQuery;
import lms.identity.tenant.application.PlatformEvents;
import lms.identity.tenant.application.TenantModuleOperations.ContractSuspension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Một lượt xử lý vòng đời hợp đồng (ADR-0012 mục 2.4), gọi bởi job mỗi giờ (hoặc trực tiếp trong test), theo thứ tự:
 * (1) áp dụng module cho hợp đồng ACTIVE tới starts_at, (2) hết hạn hợp đồng quá ân hạn (thu hồi module), (3) nhắc hạn.
 * Áp dụng đứng trước hết hạn để hợp đồng nối tiếp bao phủ trước khi hợp đồng cũ bị thu hồi.
 *
 * <p>Idempotent và an toàn khi nhiều instance chạy cùng lúc: ứng viên chọn không khóa; mỗi hợp đồng xử lý trong một
 * {@code TenantContext.callAs(tenant)} + transaction, khóa dòng tenant rồi ĐỌC LẠI hợp đồng và kiểm lại điều kiện
 * (đã xử lý rồi thì no-op: không ghi lịch sử, không phát event). Lỗi một hợp đồng chỉ log và đi tiếp.
 */
public class ProcessContractLifecycleUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessContractLifecycleUseCase.class);

    /** Số hợp đồng thực sự được xử lý trong lượt (no-op không tính) và số lỗi. */
    public record Result(int applied, int expired, int reminded, int failed) {
    }

    private final ContractRepository contracts;
    private final ContractReminderRepository reminders;
    private final ContractExecutor executor;
    private final ContractTransitions transitions;
    private final ContractPolicy policy;
    private final ListActiveTenantAdminsQuery admins;
    private final Clock clock;

    public ProcessContractLifecycleUseCase(ContractRepository contracts, ContractReminderRepository reminders,
                                           ListActiveTenantAdminsQuery admins, ContractRuntime runtime) {
        this.contracts = contracts;
        this.reminders = reminders;
        this.admins = admins;
        this.executor = runtime.executor();
        this.transitions = runtime.transitions();
        this.policy = runtime.policy();
        this.clock = runtime.clock();
    }

    public Result runOnce() {
        return runOnce(clock.instant());
    }

    public Result runOnce(Instant now) {
        int[] counts = new int[4];
        for (Contract c : contracts.findDueToApply(now)) {
            guarded(c, counts, 0, () -> apply(c.tenantId(), c.id(), now));
        }
        for (Contract c : contracts.findDueToExpire(now.minus(policy.gracePeriod()))) {
            guarded(c, counts, 1, () -> expire(c.tenantId(), c.id(), now));
        }
        for (Contract c : contracts.findReminderCandidates(now)) {
            guarded(c, counts, 2, () -> remind(c.tenantId(), c.id(), now));
        }
        return new Result(counts[0], counts[1], counts[2], counts[3]);
    }

    private void guarded(Contract c, int[] counts, int slot, java.util.function.BooleanSupplier step) {
        try {
            if (step.getAsBoolean()) counts[slot]++;
        } catch (RuntimeException e) {
            counts[3]++;
            log.error("Xử lý vòng đời hợp đồng thất bại, contractId={}, tenantId={}: {}", c.id(), c.tenantId(),
                    e.toString(), e);
        }
    }

    // ------------------------------------------------------------------ (1) áp dụng module

    private boolean apply(UUID tenantId, UUID contractId, Instant now) {
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = contracts.findById(contractId).orElse(null);
            if (c == null || c.status() != ContractStatus.ACTIVE || c.modulesApplied() || c.startsAt().isAfter(now)) {
                return false;
            }
            transitions.applyModules(c, null, now, buf);
            return true;
        });
    }

    // ------------------------------------------------------------------ (2) hết hạn

    private boolean expire(UUID tenantId, UUID contractId, Instant now) {
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = contracts.findById(contractId).orElse(null);
            if (c == null || c.status() != ContractStatus.ACTIVE || c.endsAt() == null
                    || c.endsAt().plus(policy.gracePeriod()).isAfter(now)) {
                return false;
            }
            ContractTransitions.Revoked revoked = c.modulesApplied()
                    ? transitions.revokeModules(c, ContractSuspension.EXPIRED, null, now, buf)
                    : new ContractTransitions.Revoked(List.of(), null, false);
            c.expire(now);
            contracts.save(c);
            transitions.log(null, c, "EXPIRED", now, data("modulesDisabled", revoked.disabled(),
                    "retainedModule", revoked.retained(), "tenantSuspended", revoked.suspended()));
            buf.add(PlatformEvents.operation("CONTRACT_EXPIRED", null, tenantId, c.id(),
                    "contract=" + c.id() + ", status=EXPIRED, disabled=" + revoked.disabled() + ", suspended="
                            + revoked.suspended()));
            LoggerFactory.getLogger("platform-audit").info(
                    "platform-audit operation=contract-expired tenant={} contract={} disabled={} suspended={}",
                    tenantId, c.id(), revoked.disabled(), revoked.suspended());
            return true;
        });
    }

    // ------------------------------------------------------------------ (3) nhắc hạn

    private boolean remind(UUID tenantId, UUID contractId, Instant now) {
        return executor.inTenantTransaction(tenantId, buf -> {
            Contract c = contracts.findById(contractId).orElse(null);
            if (c == null || c.status() != ContractStatus.ACTIVE || c.legacy() || c.endsAt() == null
                    || now.isBefore(c.startsAt()) || !now.isBefore(c.endsAt())) {
                return false;
            }
            LocalDate today = now.atZone(policy.zone()).toLocalDate();
            LocalDate endDate = ContractPeriod.endDate(c.endsAt(), policy.zone());
            Set<Integer> recorded = reminders.recordedDays(c.id(), c.endsAt());
            ReminderSchedule.Decision decision = ReminderSchedule.decide(policy.reminderDays(), today, endDate,
                    recorded);
            for (int skipped : decision.skipped()) {
                reminders.record(c.id(), skipped, c.endsAt(), 0, true, now);
            }
            if (decision.isEmpty()) return false;
            int days = decision.send();
            List<UUID> recipients = admins.execute(tenantId);
            if (!reminders.record(c.id(), days, c.endsAt(), recipients.size(), false, now)) return false;
            transitions.log(null, c, "REMINDER_SENT", now, data("daysBefore", days, "recipientCount",
                    recipients.size(), "skippedDays", decision.skipped()));
            if (recipients.isEmpty()) {
                log.warn("Hợp đồng {} của tenant {} sắp hết hạn (mốc {} ngày) nhưng không có quản trị viên để nhắc",
                        c.id(), tenantId, days);
            } else {
                buf.add(TenantContractExpiringSoonEvent.of(tenantId, c.id(), c.name(), endDate, days, c.endsAt(),
                        recipients, now));
            }
            return true;
        });
    }
}
