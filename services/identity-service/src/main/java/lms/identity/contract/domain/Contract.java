package lms.identity.contract.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lms.identity.contract.domain.ContractException.Reason;

/**
 * Hợp đồng thuê dịch vụ của một tenant (ADR-0012). Máy trạng thái: DRAFT → ACTIVE → EXPIRED, DRAFT/ACTIVE → CANCELLED.
 * Khoảng hiệu lực nửa mở {@code [startsAt, endsAt)}; {@code endsAt} null chỉ với hợp đồng legacy do migration tạo.
 * Java thuần: thời điểm hiện tại và ân hạn do người gọi truyền vào.
 */
public final class Contract {

    /** Nội dung sửa được (DRAFT: mọi trường; ACTIVE: chỉ internalNote). Ngày theo giờ VN. */
    public record Content(String name, Set<ContractModule> modules, LocalDate startDate, LocalDate endDate,
                          Long totalAmount, BillingCycle billingCycle, String internalNote) {
    }

    private final UUID id;
    private final UUID tenantId;
    private String name;
    private ContractStatus status;
    private final boolean legacy;
    private Instant startsAt;
    private Instant endsAt;
    private long totalAmount;
    private BillingCycle billingCycle;
    private String internalNote;
    private final Set<ContractModule> modules = EnumSet.noneOf(ContractModule.class);
    private Instant modulesAppliedAt;
    private ContractModule retainedModule;
    private String cancelReason;
    private final Instant createdAt;
    private final UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;
    private final long version;

    private Contract(UUID id, UUID tenantId, String name, ContractStatus status, boolean legacy, Instant startsAt,
                     Instant endsAt, long totalAmount, BillingCycle billingCycle, String internalNote,
                     Collection<ContractModule> modules, Instant modulesAppliedAt, ContractModule retainedModule,
                     String cancelReason, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy,
                     long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.status = status;
        this.legacy = legacy;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.totalAmount = totalAmount;
        this.billingCycle = billingCycle;
        this.internalNote = internalNote;
        this.modules.addAll(modules);
        this.modulesAppliedAt = modulesAppliedAt;
        this.retainedModule = retainedModule;
        this.cancelReason = cancelReason;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    /** Hợp đồng NHÁP mới (API): bắt buộc có ngày kết thúc (đã chốt: không tạo hợp đồng không thời hạn qua API). */
    public static Contract draft(UUID tenantId, Content content, UUID actor, Instant now, ZoneId zone) {
        Validated v = validate(content, zone);
        return new Contract(UUID.randomUUID(), tenantId, v.name, ContractStatus.DRAFT, false, v.startsAt, v.endsAt,
                v.total, content.billingCycle(), clean(content.internalNote()), content.modules(), null, null, null,
                now, actor, now, actor, 0);
    }

    public static Contract restore(UUID id, UUID tenantId, String name, ContractStatus status, boolean legacy,
                                   Instant startsAt, Instant endsAt, long totalAmount, BillingCycle billingCycle,
                                   String internalNote, Collection<ContractModule> modules, Instant modulesAppliedAt,
                                   ContractModule retainedModule, String cancelReason, Instant createdAt,
                                   UUID createdBy, Instant updatedAt, UUID updatedBy, long version) {
        return new Contract(id, tenantId, name, status, legacy, startsAt, endsAt, totalAmount, billingCycle,
                internalNote, modules, modulesAppliedAt, retainedModule, cancelReason, createdAt, createdBy,
                updatedAt, updatedBy, version);
    }

    private record Validated(String name, Instant startsAt, Instant endsAt, long total) {
    }

    private static Validated validate(Content c, ZoneId zone) {
        if (c.name() == null || c.name().isBlank()) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Tên hợp đồng không được để trống");
        }
        if (c.name().trim().length() > 255) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Tên hợp đồng tối đa 255 ký tự");
        }
        if (c.modules() == null || c.modules().isEmpty()) {
            throw new ContractException(Reason.INVALID_MODULES, "Hợp đồng phải có ít nhất một module");
        }
        if (c.startDate() == null) throw new ContractException(Reason.INVALID_CONTRACT, "Thiếu ngày bắt đầu");
        if (c.endDate() == null) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Thiếu ngày kết thúc (hợp đồng phải có thời hạn)");
        }
        if (c.endDate().isBefore(c.startDate())) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Ngày kết thúc phải từ ngày bắt đầu trở đi");
        }
        if (c.totalAmount() == null || c.totalAmount() < 0) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Tổng tiền phải là số không âm (VND)");
        }
        if (c.internalNote() != null && c.internalNote().length() > 2000) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Ghi chú nội bộ tối đa 2000 ký tự");
        }
        return new Validated(c.name().trim(), ContractPeriod.startsAt(c.startDate(), zone),
                ContractPeriod.endsAt(c.endDate(), zone), c.totalAmount());
    }

    private static String clean(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    // ------------------------------------------------------------------ pha

    /** Pha của hợp đồng ACTIVE; null với trạng thái khác. {@code grace}: ân hạn sau ngày kết thúc. */
    public ContractPhase phase(Instant now, Duration grace) {
        if (status != ContractStatus.ACTIVE) return null;
        if (now.isBefore(startsAt)) return ContractPhase.UPCOMING;
        if (endsAt == null || now.isBefore(endsAt)) return ContractPhase.IN_EFFECT;
        return ContractPhase.IN_GRACE;
    }

    /** Hợp đồng ACTIVE đang bao phủ module ở thời điểm {@code now}: tới ngày bắt đầu và chưa quá ân hạn. */
    public boolean covers(Instant now, Duration grace) {
        if (status != ContractStatus.ACTIVE || now.isBefore(startsAt)) return false;
        return endsAt == null || now.isBefore(endsAt.plus(grace));
    }

    /** Mốc hết ân hạn; null nếu không có ngày kết thúc. */
    public Instant graceEndsAt(Duration grace) {
        return endsAt == null ? null : endsAt.plus(grace);
    }

    public boolean overlaps(Contract other) {
        return ContractPeriod.overlaps(startsAt, endsAt, other.startsAt, other.endsAt);
    }

    // ------------------------------------------------------------------ chuyển trạng thái

    /**
     * Sửa nội dung. DRAFT: mọi trường. ACTIVE: chỉ {@code internalNote} (đổi trường khác → CONTRACT_NOT_EDITABLE).
     * EXPIRED/CANCELLED: không gì cả.
     *
     * @return các trường đổi: tên trường → [trước, sau] (rỗng nếu không đổi gì)
     */
    public Map<String, Object[]> update(Content c, UUID actor, Instant now, ZoneId zone) {
        Map<String, Object[]> changes = new LinkedHashMap<>();
        if (status == ContractStatus.DRAFT) {
            Validated v = validate(c, zone);
            diff(changes, "name", name, v.name);
            diff(changes, "modules", sorted(modules), sorted(c.modules()));
            diff(changes, "startsAt", startsAt, v.startsAt);
            diff(changes, "endsAt", endsAt, v.endsAt);
            diff(changes, "totalAmount", totalAmount, v.total);
            diff(changes, "billingCycle", billingCycle, c.billingCycle());
            diff(changes, "internalNote", internalNote, clean(c.internalNote()));
            this.name = v.name;
            this.modules.clear();
            this.modules.addAll(c.modules());
            this.startsAt = v.startsAt;
            this.endsAt = v.endsAt;
            this.totalAmount = v.total;
            this.billingCycle = c.billingCycle();
            this.internalNote = clean(c.internalNote());
        } else if (status == ContractStatus.ACTIVE) {
            Validated v = validate(c, zone);
            boolean sameBody = name.equals(v.name) && modules.equals(Set.copyOf(c.modules()))
                    && Objects.equals(startsAt, v.startsAt) && Objects.equals(endsAt, v.endsAt)
                    && totalAmount == v.total && Objects.equals(billingCycle, c.billingCycle());
            if (!sameBody) {
                throw new ContractException(Reason.CONTRACT_NOT_EDITABLE,
                        "Hợp đồng đã hiệu lực chỉ được sửa ghi chú nội bộ (kéo dài dùng thao tác extend, đổi khác thì hủy và tạo hợp đồng mới)");
            }
            diff(changes, "internalNote", internalNote, clean(c.internalNote()));
            this.internalNote = clean(c.internalNote());
        } else {
            throw new ContractException(Reason.CONTRACT_NOT_EDITABLE,
                    "Hợp đồng " + status + " không được sửa");
        }
        if (!changes.isEmpty()) touch(actor, now);
        return changes;
    }

    private static Object sorted(Collection<ContractModule> m) {
        return m.stream().sorted().map(Enum::name).toList();
    }

    private static void diff(Map<String, Object[]> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.put(field, new Object[] {before, after});
    }

    /** NHÁP → HIỆU LỰC (kiểm chồng lấn do use case làm trước). */
    public void activate(UUID actor, Instant now) {
        if (status != ContractStatus.DRAFT) {
            throw new ContractException(Reason.CONTRACT_NOT_EDITABLE, "Chỉ kích hoạt được hợp đồng NHÁP, hiện là " + status);
        }
        this.status = ContractStatus.ACTIVE;
        touch(actor, now);
    }

    /** Kéo dài ngày kết thúc của hợp đồng ACTIVE không legacy (chỉ dời ra sau). */
    public Instant extend(LocalDate newEndDate, UUID actor, Instant now, ZoneId zone) {
        if (status != ContractStatus.ACTIVE || legacy || endsAt == null) {
            throw new ContractException(Reason.CONTRACT_NOT_EDITABLE,
                    "Chỉ kéo dài được hợp đồng ACTIVE không phải hợp đồng chuyển tiếp (legacy)");
        }
        if (newEndDate == null) throw new ContractException(Reason.INVALID_CONTRACT, "Thiếu ngày kết thúc mới");
        Instant newEnds = ContractPeriod.endsAt(newEndDate, zone);
        if (!newEnds.isAfter(endsAt)) {
            throw new ContractException(Reason.INVALID_CONTRACT,
                    "Ngày kết thúc mới phải sau ngày kết thúc hiện tại (rút ngắn thì dùng hủy)");
        }
        Instant old = endsAt;
        this.endsAt = newEnds;
        touch(actor, now);
        return old;
    }

    /** Hủy (DRAFT hoặc ACTIVE) kèm lý do. */
    public void cancel(String reason, UUID actor, Instant now) {
        if (status != ContractStatus.DRAFT && status != ContractStatus.ACTIVE) {
            throw new ContractException(Reason.CONTRACT_NOT_EDITABLE, "Không hủy được hợp đồng " + status);
        }
        if (reason == null || reason.isBlank()) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Hủy hợp đồng phải có lý do");
        }
        if (reason.trim().length() > 1000) {
            throw new ContractException(Reason.INVALID_CONTRACT, "Lý do hủy tối đa 1000 ký tự");
        }
        this.status = ContractStatus.CANCELLED;
        this.cancelReason = reason.trim();
        touch(actor, now);
    }

    /** Job: ACTIVE quá ân hạn → EXPIRED (actor null = hệ thống). */
    public void expire(Instant now) {
        if (status != ContractStatus.ACTIVE) {
            throw new ContractException(Reason.CONTRACT_NOT_EDITABLE, "Chỉ hợp đồng ACTIVE mới hết hạn, hiện là " + status);
        }
        this.status = ContractStatus.EXPIRED;
        this.updatedAt = now;
        this.updatedBy = null;
    }

    /** Cắt hợp đồng legacy tại ngày bắt đầu của hợp đồng thay thế (vào ân hạn từ đó). */
    public void cutLegacyAt(Instant newEndsAt, UUID actor, Instant now) {
        if (!legacy || status != ContractStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ cắt hợp đồng legacy đang ACTIVE");
        }
        if (!newEndsAt.isAfter(startsAt)) {
            throw new ContractException(Reason.CONTRACT_OVERLAP,
                    "Hợp đồng mới phải bắt đầu sau ngày bắt đầu của hợp đồng chuyển tiếp hiện có");
        }
        this.endsAt = newEndsAt;
        touch(actor, now);
    }

    public void markModulesApplied(Instant now) {
        this.modulesAppliedAt = now;
        this.updatedAt = now;
    }

    public void retain(ContractModule module) {
        this.retainedModule = module;
    }

    public void clearRetained() {
        this.retainedModule = null;
    }

    private void touch(UUID actor, Instant now) {
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    // ------------------------------------------------------------------ truy cập

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String name() { return name; }
    public ContractStatus status() { return status; }
    public boolean legacy() { return legacy; }
    public Instant startsAt() { return startsAt; }
    /** Null chỉ với hợp đồng legacy chưa bị cắt. */
    public Instant endsAt() { return endsAt; }
    public long totalAmount() { return totalAmount; }
    public BillingCycle billingCycle() { return billingCycle; }
    public String internalNote() { return internalNote; }
    public Set<ContractModule> modules() { return Collections.unmodifiableSet(modules); }
    public Instant modulesAppliedAt() { return modulesAppliedAt; }
    public boolean modulesApplied() { return modulesAppliedAt != null; }
    public ContractModule retainedModule() { return retainedModule; }
    public String cancelReason() { return cancelReason; }
    public Instant createdAt() { return createdAt; }
    public UUID createdBy() { return createdBy; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
    public long version() { return version; }
}
