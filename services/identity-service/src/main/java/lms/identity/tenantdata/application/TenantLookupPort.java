package lms.identity.tenantdata.application;

import java.util.Optional;
import java.util.UUID;

/** Tra mã tenant (manifest) và kiểm tenant tồn tại; cầu nối sang context tenant do infrastructure nối. */
public interface TenantLookupPort {

    Optional<String> codeOf(UUID tenantId);
}
