package lms.identity.tenant.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;

/**
 * Bước chung của "tạo tenant" và "thêm quản trị viên": có được tài khoản (tạo mới với mật khẩu tạm, hoặc tài khoản có
 * sẵn) là thành viên ACTIVE của tenant hiện tại. Gọi trong TenantContext của tenant đích và trong transaction.
 */
final class TenantAdminAssignment {

    private final MembershipRepository memberships;
    private final TenantAccountPort accounts;

    TenantAdminAssignment(MembershipRepository memberships, TenantAccountPort accounts) {
        this.memberships = memberships;
        this.accounts = accounts;
    }

    static void requireExactlyOne(NewAdmin newAdmin, UUID existingUserId) {
        if ((newAdmin == null) == (existingUserId == null)) {
            throw new InvalidTenantException("Cần đúng một trong newAdmin (tài khoản mới) hoặc existingUserId (tài khoản có sẵn)");
        }
    }

    /** Trả userId của tài khoản đã là thành viên ACTIVE của tenantId. */
    UUID ensureMember(UUID tenantId, NewAdmin newAdmin, UUID existingUserId) {
        return ensureMember(tenantId, newAdmin, existingUserId, TenantCreationOptions.STANDARD);
    }

    UUID ensureMember(UUID tenantId, NewAdmin newAdmin, UUID existingUserId, TenantCreationOptions options) {
        if (newAdmin != null) {
            return options == TenantCreationOptions.STANDARD
                    ? accounts.createAccount(newAdmin)
                    : accounts.createAccount(newAdmin, options.adminUserId(), options.adminMustChangePassword());
        }
        attachExisting(tenantId, existingUserId);
        return existingUserId;
    }

    /** Gắn tài khoản có sẵn: tạo membership hoặc bật lại membership đã tắt (người vận hành quyết định tường minh). */
    void attachExisting(UUID tenantId, UUID userId) {
        if (!accounts.exists(userId)) {
            throw new PlatformAccountException(PlatformAccountException.Reason.NOT_FOUND,
                    "Không tìm thấy tài khoản " + userId);
        }
        Optional<Membership> existing = memberships.find(userId, tenantId);
        if (existing.isEmpty()) {
            memberships.save(Membership.create(userId, tenantId, null));
        } else if (!existing.get().isActive()) {
            Membership membership = existing.get();
            membership.enable();
            memberships.save(membership);
        }
    }
}
