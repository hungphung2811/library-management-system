package lms.identity.user.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.tenant.application.TenantNameQuery;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import lms.tenant.TenantContext;

/**
 * Tra địa chỉ nhận thông báo theo lô cho support-service (ADR-0030 D5). Trả TỐI THIỂU: userId, email, tên hiển thị,
 * cờ active và tên tenant; không có điện thoại, ngày sinh, địa chỉ, số thẻ. Chỉ người có membership ACTIVE trong tenant
 * (id ngoài tenant hoặc không tồn tại bị bỏ qua, không báo lỗi để không lộ sự tồn tại, ADR-0006). Email ưu tiên
 * {@code reader_profiles.email} của tenant, không có thì {@code users.email}. Chạy trong TenantContext của tenant được
 * hỏi và một transaction (RLS của reader_profiles cần app.tenant_id lúc mượn kết nối).
 */
public class LookupNotificationRecipientsUseCase {

    public static final int MAX_IDS = 100;

    public record Recipient(UUID userId, String email, String displayName, boolean active) {
        @Override
        public String toString() {
            return "Recipient[userId=" + userId + "]";
        }
    }

    public record Result(String tenantDisplayName, List<Recipient> recipients) {
    }

    private final UserRepository users;
    private final ReaderProfileRepository readers;
    private final TenantNameQuery tenantNames;
    private final TransactionalRunner tx;

    public LookupNotificationRecipientsUseCase(UserRepository users, ReaderProfileRepository readers,
                                               TenantNameQuery tenantNames, TransactionalRunner tx) {
        this.users = users;
        this.readers = readers;
        this.tenantNames = tenantNames;
        this.tx = tx;
    }

    public Result execute(UUID tenantId, Collection<UUID> userIds) {
        if (tenantId == null) throw new InvalidUserException("tenantId không được null");
        Collection<UUID> ids = userIds == null ? List.of() : userIds;
        if (ids.size() > MAX_IDS) throw new InvalidUserException("Tối đa " + MAX_IDS + " id mỗi lần tra");
        if (ids.stream().anyMatch(java.util.Objects::isNull)) throw new InvalidUserException("id không được null");
        String tenantName = tenantNames.nameOf(tenantId).orElse("");
        if (ids.isEmpty()) return new Result(tenantName, List.of());
        List<Recipient> recipients = TenantContext.callAs(tenantId, () -> tx.inTransaction(() -> resolve(tenantId, ids)));
        return new Result(tenantName, recipients);
    }

    private List<Recipient> resolve(UUID tenantId, Collection<UUID> ids) {
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(ids);
        List<User> members = users.findAllActiveInTenant(unique, tenantId);
        Map<UUID, ReaderProfile> profiles = readers.findByUserIds(members.stream().map(User::id).toList()).stream()
                .collect(Collectors.toMap(ReaderProfile::userId, p -> p, (a, b) -> a));
        List<Recipient> out = new ArrayList<>(members.size());
        for (User u : members) {
            ReaderProfile p = profiles.get(u.id());
            boolean readerEmail = p != null && p.email() != null && !p.email().isBlank();
            String email = readerEmail ? p.email().trim() : u.email();
            String name = p != null && p.fullName() != null && !p.fullName().isBlank() ? p.fullName() : u.fullName();
            out.add(new Recipient(u.id(), email, name, u.status() == UserStatus.ACTIVE));
        }
        return out;
    }
}
