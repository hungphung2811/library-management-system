package lms.identity.user.application;

import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.util.UUID;
import lms.tenant.TenantContext;

/**
 * Quy tắc ai được xem và thu hồi phiên của ai (ADR-0017 mục 2.9.3), dùng chung cho cả ba use case:
 * <ol>
 *   <li>G1: phải có tenant hiện tại ({@link TenantContext#require()}, thiếu thì 403 TENANT_REQUIRED).</li>
 *   <li>G2: người đích có membership ở tenant hiện tại, không thì 404 USER_NOT_FOUND.</li>
 *   <li>G4: người đích là platform admin: chỉ platform admin được thao tác.</li>
 *   <li>G3: người đích có role is_admin trong tenant hiện tại: chỉ người gọi admin (hoặc platform admin) được thao tác.</li>
 *   <li>G5: tự thao tác lên mình vẫn qua các quy tắc trên. G6: không kiểm số tenant của tài khoản (chỉ chạm phiên của
 *       tenant hiện tại, không đụng tài khoản toàn cục).</li>
 * </ol>
 */
class SessionManagementGuard {

    private final UserAccountGuard accounts;
    private final AccountCredentialPort credentials;
    private final TargetAdminLookupPort adminLookup;

    SessionManagementGuard(UserRepository repository, AccountCredentialPort credentials,
                           TargetAdminLookupPort adminLookup) {
        this.accounts = new UserAccountGuard(repository, credentials);
        this.credentials = credentials;
        this.adminLookup = adminLookup;
    }

    /** @return tenant hiện tại (đã qua G1) */
    UUID require(SessionCaller caller, UUID targetUserId) {
        UUID tenantId = TenantContext.require();
        accounts.requireMember(targetUserId, tenantId);
        if (!caller.platformAdmin() && credentials.isPlatformAdmin(targetUserId)) {
            throw new UserOperationNotAllowedException(
                    "Tài khoản quản trị nền tảng: chỉ người vận hành nền tảng được thao tác phiên");
        }
        if (!caller.admin() && !caller.platformAdmin() && adminLookup.isTenantAdmin(targetUserId)) {
            throw new UserOperationNotAllowedException(
                    "Người dùng có role quản trị: chỉ quản trị viên mới xem hoặc thu hồi được phiên");
        }
        return tenantId;
    }
}
