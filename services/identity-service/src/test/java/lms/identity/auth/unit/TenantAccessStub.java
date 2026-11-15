package lms.identity.auth.unit;

import java.util.ArrayList;
import java.util.List;
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
        return new TenantChoice(TENANT, List.of(SUMMARY), department);
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

    @Override
    public List<TenantSummary> available(UUID userId) {
        return List.of(SUMMARY);
    }

    @Override
    public void addMember(UUID userId, UUID tenantId) {
        addedMembers.add(tenantId);
    }
}
