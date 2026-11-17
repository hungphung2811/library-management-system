package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tập module của một tenant (ADR-0009). Bất biến: luôn có ít nhất một module bật. Giữ dòng khi tắt để biết lần đổi
 * cuối; module chưa từng bật không có dòng và được coi là tắt.
 */
public final class TenantModules {

    private final Map<ModuleCode, TenantModuleEntry> entries = new EnumMap<>(ModuleCode.class);

    private TenantModules(Collection<TenantModuleEntry> existing) {
        for (TenantModuleEntry e : existing) entries.put(e.module(), e);
    }

    /** Dựng lại từ dữ liệu đã lưu (không kiểm bất biến: dữ liệu cũ do migration backfill). */
    public static TenantModules restore(Collection<TenantModuleEntry> existing) {
        return new TenantModules(existing);
    }

    /**
     * Tập module ban đầu khi tạo tenant: null = mặc định {@link ModuleCode#DEFAULT}; rỗng thì từ chối; trùng được gộp.
     *
     * @throws InvalidTenantModulesException nếu rỗng
     * @throws UnknownModuleException nếu có mã không hợp lệ
     */
    public static TenantModules initial(Collection<String> requested, Instant at, UUID by) {
        Set<ModuleCode> chosen = new LinkedHashSet<>();
        if (requested == null) {
            chosen.add(ModuleCode.DEFAULT);
        } else {
            if (requested.isEmpty()) {
                throw new InvalidTenantModulesException("Tenant phải có ít nhất một module");
            }
            for (String code : requested) chosen.add(ModuleCode.parse(code));
        }
        return new TenantModules(chosen.stream().map(m -> new TenantModuleEntry(m, true, at, by)).toList());
    }

    public boolean isEnabled(ModuleCode module) {
        TenantModuleEntry e = entries.get(module);
        return e != null && e.enabled();
    }

    /** Module đang bật, theo thứ tự khai báo của enum. */
    public List<ModuleCode> enabled() {
        return entries.values().stream().filter(TenantModuleEntry::enabled).map(TenantModuleEntry::module).toList();
    }

    /** Mọi module của hệ thống kèm trạng thái (module chưa từng bật: tắt, không có thời điểm). */
    public List<TenantModuleEntry> all() {
        return Arrays.stream(ModuleCode.values()).map(this::entry).toList();
    }

    public TenantModuleEntry entry(ModuleCode module) {
        return entries.getOrDefault(module, TenantModuleEntry.neverEnabled(module));
    }

    /** Bật module; rỗng nếu đã bật sẵn (idempotent, không đổi gì). */
    public Optional<TenantModuleEntry> enable(ModuleCode module, Instant at, UUID by) {
        if (isEnabled(module)) return Optional.empty();
        TenantModuleEntry changed = new TenantModuleEntry(module, true, at, by);
        entries.put(module, changed);
        return Optional.of(changed);
    }

    /**
     * Tắt module; rỗng nếu đang tắt (idempotent).
     *
     * @throws TenantModuleRequiredException nếu là module bật cuối cùng
     */
    public Optional<TenantModuleEntry> disable(ModuleCode module, Instant at, UUID by) {
        if (!isEnabled(module)) return Optional.empty();
        if (enabled().size() == 1) {
            throw new TenantModuleRequiredException("Không thể tắt module " + module
                    + ": tenant phải còn ít nhất một module (muốn ngưng thì tạm ngưng tenant)");
        }
        TenantModuleEntry changed = new TenantModuleEntry(module, false, at, by);
        entries.put(module, changed);
        return Optional.of(changed);
    }

    /** Các dòng hiện có (để lưu khi tạo tenant). */
    public Collection<TenantModuleEntry> entries() {
        return List.copyOf(entries.values());
    }
}
