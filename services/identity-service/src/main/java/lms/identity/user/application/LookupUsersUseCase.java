package lms.identity.user.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserRepository;
import lms.tenant.TenantContext;

/**
 * Tra họ tên người dùng theo lô trong tenant hiện tại (ADR-0018 mục 2.3, 2.5). Bảng users là toàn cục nên lọc tường minh
 * qua tenant_memberships: id không thuộc tenant (hoặc không tồn tại) bị bỏ qua, không báo lỗi để không lộ sự tồn tại.
 */
public class LookupUsersUseCase {

    public static final int MAX_IDS = 100;

    private final UserRepository repository;

    public LookupUsersUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public List<UserNameView> execute(Collection<java.util.UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > MAX_IDS) throw new InvalidUserException("Tối đa " + MAX_IDS + " id mỗi lần tra");
        if (ids.stream().anyMatch(java.util.Objects::isNull)) throw new InvalidUserException("id không được null");
        java.util.UUID tenantId = TenantContext.require();
        return repository.findAllInTenant(new LinkedHashSet<>(ids), tenantId).stream()
                .map(u -> new UserNameView(u.id(), u.fullName())).toList();
    }
}
