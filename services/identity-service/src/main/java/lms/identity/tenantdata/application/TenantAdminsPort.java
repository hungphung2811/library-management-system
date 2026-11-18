package lms.identity.tenantdata.application;

import java.util.List;
import java.util.UUID;

/** Id quản trị viên còn hoạt động của tenant (người nhận thông báo gói xuất). Chỉ id, không email. */
public interface TenantAdminsPort {

    List<UUID> adminsOf(UUID tenantId);
}
