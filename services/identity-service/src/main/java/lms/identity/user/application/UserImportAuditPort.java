package lms.identity.user.application;

import lms.identity.user.domain.UsersImportedEvent;

/** Cổng audit của nhập người dùng từ Excel: một bản ghi tóm tắt mỗi lần confirm (best-effort, ADR-0001). */
public interface UserImportAuditPort {

    void record(UsersImportedEvent event);
}
