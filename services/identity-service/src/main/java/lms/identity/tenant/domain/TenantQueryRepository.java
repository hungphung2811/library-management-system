package lms.identity.tenant.domain;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Truy vấn phục vụ quản lý nền tảng (tìm tenant, đếm thành viên); tách khỏi TenantRepository của luồng đăng nhập. */
public interface TenantQueryRepository {

    TenantPage search(TenantCriteria criteria);

    /** Số thành viên ACTIVE theo từng tenant; tenant không có thành viên có giá trị 0. */
    Map<UUID, Long> countActiveMembers(Collection<UUID> tenantIds);
}
