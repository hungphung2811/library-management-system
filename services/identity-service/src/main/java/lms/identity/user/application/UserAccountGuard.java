package lms.identity.user.application;

import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.util.UUID;

/**
 * Quy tắc quản lý người dùng theo tenant (mục 5.6 spec multi-tenant), dùng chung cho các use case của context user.
 * Người không có membership ở tenant của người gọi: coi như không tồn tại (404). Thao tác lên tài khoản toàn cục (họ
 * tên, email, mật khẩu, bật/tắt): chỉ khi tài khoản chỉ thuộc đúng một tenant và không phải platform admin; ngược lại 403.
 */
class UserAccountGuard {

    private final UserRepository repository;
    private final AccountCredentialPort credentials;

    UserAccountGuard(UserRepository repository, AccountCredentialPort credentials) {
        this.repository = repository;
        this.credentials = credentials;
    }

    /** Người dùng (có bản ghi users) thuộc tenant; không thì UserNotFoundException. */
    TenantUser requireInTenant(UUID userId, UUID tenantId) {
        return repository.findInTenant(userId, tenantId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    /** Có membership ở tenant (kể cả tài khoản chỉ có credential); không thì UserNotFoundException. */
    void requireMember(UUID userId, UUID tenantId) {
        if (!repository.hasMembership(userId, tenantId)) throw new UserNotFoundException(userId);
    }

    /** Gọi sau requireInTenant/requireMember: chỉ thuộc một tenant (tenant đó) và không phải platform admin. */
    void requireAccountManageable(UUID userId) {
        if (repository.countMembershipsOf(userId) != 1) {
            throw new UserOperationNotAllowedException(
                    "Tài khoản thuộc nhiều tenant: chỉ chính người dùng hoặc người vận hành mới thay đổi được");
        }
        if (credentials.isPlatformAdmin(userId)) {
            throw new UserOperationNotAllowedException(
                    "Tài khoản quản trị nền tảng: quản trị viên tenant không thay đổi được");
        }
    }
}
