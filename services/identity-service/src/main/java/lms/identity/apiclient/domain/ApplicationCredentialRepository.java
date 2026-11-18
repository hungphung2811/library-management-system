package lms.identity.apiclient.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bảng toàn cục application_credentials (identity_db, không RLS). Người gọi đã xác nhận Application thuộc tenant. */
public interface ApplicationCredentialRepository {

    ApplicationCredential save(ApplicationCredential credential);

    /** Credential của một Application, theo ngày tạo tăng dần. */
    List<ApplicationCredential> findByApplication(UUID applicationId);

    /** Nạp theo lô cho danh sách (một câu lệnh). */
    List<ApplicationCredential> findByApplications(Collection<UUID> applicationIds);

    /** Credential theo id VÀ Application (id của Application khác: rỗng). */
    Optional<ApplicationCredential> findByIdAndApplication(UUID credentialId, UUID applicationId);
}
