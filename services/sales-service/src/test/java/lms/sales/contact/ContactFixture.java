package lms.sales.contact;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.sales.contact.application.AnonymizeExpiredShopContactsUseCase;
import lms.sales.contact.application.AnonymizeShopContactBatchUseCase;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactSubmissionPolicy;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.SalesException;

/**
 * Bộ giả trong bộ nhớ cho test contact (không Spring): repository theo đúng hợp đồng port (version tăng khi cập nhật,
 * lọc theo tenant như RLS), audit, danh bạ tenant, đồng hồ chỉnh được, cấu hình lưu giữ theo tenant.
 */
public final class ContactFixture {

    public final UUID tenant = UUID.randomUUID();
    public final UUID user = UUID.randomUUID();
    public final UUID staff = UUID.randomUUID();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T03:00:00Z"));
    public final List<SalesAuditPort.AuditEntry> audits = new ArrayList<>();
    public final Set<UUID> knownTenants = new LinkedHashSet<>();
    public final Map<UUID, Integer> retentionMonths = new HashMap<>();
    public final Map<UUID, ShopContactRequest> rows = new LinkedHashMap<>();

    public final ShopContactRepository repository = new InMemoryRepository();
    public final SalesAuditPort auditPort = audits::add;
    public final TenantDirectoryPort tenantDirectory = new TenantDirectoryPort() {
        @Override
        public void touch(UUID tenantId) {
            knownTenants.add(tenantId);
        }

        @Override
        public List<UUID> listAll() {
            return List.copyOf(knownTenants);
        }
    };
    public final SalesSettingsPort settingsPort = new SalesSettingsPort() {
        @Override
        public Optional<SalesSettings> find(UUID tenantId) {
            return Optional.of(SalesSettings.restore(tenantId, 500, true, 7, 0L, 5,
                    retentionMonths.getOrDefault(tenantId, 12), 0L, clock.instant(), null));
        }

        @Override
        public SalesSettings save(SalesSettings settings) {
            throw new UnsupportedOperationException();
        }
    };
    public final GetSalesSettingsUseCase settings = new GetSalesSettingsUseCase(settingsPort);
    public final ContactSubmissionPolicy policy = new ContactSubmissionPolicy(5, Duration.ofMinutes(10));
    public final SubmitShopContactUseCase submit =
            new SubmitShopContactUseCase(repository, policy, tenantDirectory, auditPort, clock);
    public final ShopContactQueryUseCases queries = new ShopContactQueryUseCases(repository);
    public final ShopContactWorkflowUseCases workflow = new ShopContactWorkflowUseCases(repository, auditPort, clock);
    public final AnonymizeShopContactBatchUseCase batch = new AnonymizeShopContactBatchUseCase(repository, auditPort);
    public final AnonymizeExpiredShopContactsUseCase expire =
            new AnonymizeExpiredShopContactsUseCase(settings, batch, clock, 2);

    public SubmitShopContactUseCase.Outcome send(UUID tenantId, UUID userId, String message) {
        return submit.execute(new SubmitShopContactUseCase.Command(tenantId, userId,
                lms.sales.contact.domain.ContactTopic.ORDER, "Nguyễn Văn Bình", "binh@example.vn", "0912345678",
                message, true));
    }

    public SubmitShopContactUseCase.Outcome send(String message) {
        return send(tenant, user, message);
    }

    public ShopContactRequest only(UUID tenantId) {
        return rows.values().stream().filter(r -> r.tenantId().equals(tenantId))
                .max(Comparator.comparing(ShopContactRequest::createdAt)).orElseThrow();
    }

    public long count(UUID tenantId) {
        return rows.values().stream().filter(r -> r.tenantId().equals(tenantId)).count();
    }

    private final class InMemoryRepository implements ShopContactRepository {
        @Override
        public ShopContactRequest insert(ShopContactRequest r) {
            rows.put(r.id(), r);
            return r;
        }

        @Override
        public ShopContactRequest update(ShopContactRequest r) {
            ShopContactRequest old = rows.get(r.id());
            if (old == null) {
                throw ShopContactRequest.notFound();
            }
            if (old.version() != r.version()) {
                throw ShopContactRequest.stateConflict("lệch version");
            }
            ShopContactRequest saved = ShopContactRequest.restore(r.id(), r.tenantId(), r.customerUserId(),
                    r.topic(), r.message(), r.contactName(), r.contactEmail(), r.contactPhone(), r.consentAt(),
                    r.status(), r.version() + 1, r.createdAt(), r.closedAt(), r.piiAnonymizedAt());
            rows.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<ShopContactRequest> findById(UUID tenantId, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(r -> r.tenantId().equals(tenantId));
        }

        @Override
        public PageResult<ShopContactRequest> search(UUID tenantId, SearchQuery q) {
            List<ShopContactRequest> all = rows.values().stream().filter(r -> r.tenantId().equals(tenantId))
                    .filter(r -> q.status() == null || r.status() == q.status())
                    .filter(r -> q.topic() == null || r.topic() == q.topic())
                    .filter(r -> q.from() == null || !r.createdAt().isBefore(q.from()))
                    .filter(r -> q.to() == null || r.createdAt().isBefore(q.to()))
                    .sorted(Comparator.comparing(ShopContactRequest::createdAt).reversed()).toList();
            int from = Math.min(all.size(), q.page() * q.size());
            return new PageResult<>(all.subList(from, Math.min(all.size(), from + q.size())), all.size(), q.page(),
                    q.size());
        }

        @Override
        public List<ShopContactRequest> findBySenderSince(UUID tenantId, UUID customerUserId, Instant since) {
            return rows.values().stream().filter(r -> r.tenantId().equals(tenantId)
                    && customerUserId.equals(r.customerUserId()) && r.createdAt().isAfter(since)).toList();
        }

        @Override
        public List<UUID> findDueForAnonymization(UUID tenantId, Instant cutoff, int limit) {
            return rows.values().stream().filter(r -> r.tenantId().equals(tenantId) && !r.isAnonymized()
                            && r.createdAt().isBefore(cutoff))
                    .sorted(Comparator.comparing(ShopContactRequest::createdAt)).limit(limit)
                    .map(ShopContactRequest::id).toList();
        }

        @Override
        public int anonymizeAll(UUID tenantId, List<UUID> ids, Instant now) {
            int n = 0;
            for (UUID id : ids) {
                ShopContactRequest r = rows.get(id);
                if (r != null && r.tenantId().equals(tenantId) && !r.isAnonymized()) {
                    ShopContactRequest a = r.anonymize(now);
                    rows.put(id, ShopContactRequest.restore(a.id(), a.tenantId(), null, a.topic(), null, null, null,
                            null, a.consentAt(), a.status(), r.version() + 1, a.createdAt(), a.closedAt(),
                            a.piiAnonymizedAt()));
                    n++;
                }
            }
            return n;
        }
    }

    /** Đồng hồ chỉnh được. */
    public static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        public void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Chờ lỗi nghiệp vụ: trả mã. */
    public static lms.sales.shared.domain.ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (SalesException e) {
            return e.code();
        }
        throw new AssertionError("Không có SalesException");
    }
}
