package lms.sales.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.contact.domain.ContactSubmissionPolicy;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Khách gửi liên hệ (ADR-0015 R2..R5): một transaction sales_db. Chuẩn hóa/kiểm ở domain, đọc các lần gửi gần đây của
 * CHÍNH người gửi, quyết định bởi {@link ContactSubmissionPolicy} (trùng: không ghi, coi như đã nhận; vượt giới hạn:
 * 429). Tạo mới thì ghi danh bạ tenant (để job ẩn danh thấy tenant chỉ có liên hệ) và audit không PII, actor null.
 */
public class SubmitShopContactUseCase {

    private final ShopContactRepository repository;
    private final ContactSubmissionPolicy policy;
    private final TenantDirectoryPort tenants;
    private final SalesAuditPort audit;
    private final Clock clock;

    public SubmitShopContactUseCase(ShopContactRepository repository, ContactSubmissionPolicy policy,
                                    TenantDirectoryPort tenants, SalesAuditPort audit, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    public enum Outcome { CREATED, DUPLICATE }

    public record Command(UUID tenantId, UUID userId, ContactTopic topic, String fullName, String email, String phone,
                          String message, Boolean consent) {
        @Override
        public String toString() { // dữ liệu cá nhân: không in
            return "SubmitShopContactUseCase.Command[***]";
        }
    }

    @Transactional
    public Outcome execute(Command c) {
        if (c.userId() == null) {
            throw new SalesException(ErrorCode.USER_REQUIRED, "Cần đăng nhập để gửi liên hệ");
        }
        Instant now = clock.instant();
        ShopContactRequest request = ShopContactRequest.submit(UUID.randomUUID(), c.tenantId(), c.userId(), c.topic(),
                c.fullName(), c.email(), c.phone(), c.message(), c.consent(), now);
        List<ContactSubmissionPolicy.Recent> recent = repository
                .findBySenderSince(c.tenantId(), c.userId(), now.minus(ContactSubmissionPolicy.LIMIT_WINDOW)).stream()
                .map(r -> new ContactSubmissionPolicy.Recent(r.topic(), r.message(), r.createdAt())).toList();
        switch (policy.decide(recent, request.topic(), request.message(), now)) {
            case DUPLICATE:
                return Outcome.DUPLICATE;
            case LIMIT_EXCEEDED:
                throw new SalesException(ErrorCode.SHOP_CONTACT_LIMIT_EXCEEDED,
                        "Bạn đã gửi quá nhiều yêu cầu liên hệ, vui lòng thử lại sau");
            default:
                break;
        }
        repository.insert(request);
        tenants.touch(c.tenantId());
        audit.record(new SalesAuditPort.AuditEntry(c.tenantId(), SalesAuditPort.Actions.SHOP_CONTACT_CREATED,
                "ShopContactRequest", request.id(), null, now, Map.of("topic", request.topic().name())));
        return Outcome.CREATED;
    }
}
