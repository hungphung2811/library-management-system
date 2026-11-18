package lms.identity.apiclient.domain;

import java.util.List;
import java.util.UUID;

/** Lịch sử chỉ thêm (application_history; trigger chặn UPDATE/DELETE, identity_app bị REVOKE). */
public interface ApplicationHistoryRepository {

    void append(ApplicationHistoryEntry entry);

    /** Mới nhất trước, lọc tường minh theo (tenantId, applicationId), tối đa {@code limit} dòng. */
    List<ApplicationHistoryEntry> findByApplication(UUID tenantId, UUID applicationId, int limit);
}
