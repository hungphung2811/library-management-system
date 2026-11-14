package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.domain.Permission;

public interface SavePermissionPort {
    Permission save(Permission permission);

    /** Chèn khóa phát hiện được nếu chưa có; khóa đã có thì chỉ điền thông tin endpoint còn trống. Idempotent. */
    void registerDiscovered(Permission permission);
}
