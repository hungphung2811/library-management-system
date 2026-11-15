package lms.identity.tenant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    /** @throws DuplicateTenantException nếu mã đã tồn tại */
    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    Optional<Tenant> findByCode(String code);

    List<Tenant> findAll();
}
