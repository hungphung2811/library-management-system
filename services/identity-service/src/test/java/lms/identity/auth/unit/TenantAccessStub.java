package lms.identity.auth.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;

/** Port tenant giả cho test của auth: mọi người dùng thuộc đúng một tenant cố định. */
class TenantAccessStub implements TenantAccessPort {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final TenantSummary SUMMARY = new TenantSummary(TENANT, "default", "Thư viện mặc định");

    final List<UUID> addedMembers = new ArrayList<>();

    /** Phòng ban của người dùng trong tenant (membership); null = chưa gán. */
    UUID department;

    TenantChoice choice() {
        // TokenService lấy module từ choice.available() (ADR-0022), nên summary mang đúng module của stub
        TenantSummary withModules = new TenantSummary(SUMMARY.id(), SUMMARY.code(), SUMMARY.name(), modules);
        return new TenantChoice(TENANT, List.of(withModules), department);
    }

    @Override
    public TenantChoice selectForLogin(UUID userId, String tenantCodeOrNull, boolean platformAdmin) {
        return choice();
    }

    @Override
    public TenantChoice switchTo(UUID userId, UUID tenantId) {
        return choice();
    }

    @Override
    public TenantChoice verify(UUID userId, UUID tenantId) {
        return choice();
    }

    /** Module đang bật của mọi tenant trong stub (mặc định LENDING). */
    List<String> modules = List.of("LENDING");

    @Override
    public List<String> modules(UUID tenantId) {
        return modules;
    }

    @Override
    public List<TenantSummary> available(UUID userId) {
        return List.of(SUMMARY);
    }

    @Override
    public void addMember(UUID userId, UUID tenantId, UUID departmentId) {
        addedMembers.add(tenantId);
    }

    /** Tenant dùng gần nhất trả về cho mọi người dùng; null = chưa có. */
    UUID lastTenant;

    @Override
    public Optional<UUID> lastTenant(UUID userId) {
        return Optional.ofNullable(lastTenant);
    }

    @Override
    public boolean isMember(UUID userId, UUID tenantId) {
        return false;
    }

    @Override
    public boolean hasAnyMembership(UUID userId) {
        return false;
    }
}
