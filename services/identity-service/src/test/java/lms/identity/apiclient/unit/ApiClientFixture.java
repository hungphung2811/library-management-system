package lms.identity.apiclient.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.identity.apiclient.application.ApplicationPolicy;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryEntry;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ApplicationStatus;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.StorefrontOriginPolicy;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;

/** Bộ dựng use case apiclient với kho trong bộ nhớ (mô phỏng truy vấn theo (tenantId, id) của adapter thật). */
class ApiClientFixture {

    static final Instant START = Instant.parse("2026-11-18T01:00:00Z");

    /** Đồng hồ chỉnh được để thử hạn dùng. */
    static final class MutableClock extends Clock {
        Instant now = START;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    final MutableClock clock = new MutableClock();
    final FakeApplications applications = new FakeApplications();
    final FakeCredentials credentials = new FakeCredentials();
    final FakeHistory history = new FakeHistory();
    final FakeOrigins originRows = new FakeOrigins();
    final List<DomainEvent> events = new ArrayList<>();
    final PublishPlatformEventPort publisher = events::add;
    final TransactionalRunner tx = new TransactionalRunner();
    final SecureRandom random = new SecureRandom();
    final TenantRepository tenants = mock(TenantRepository.class);
    boolean contract = true;

    final ApplicationPolicy policy;
    final CreateApplicationUseCase create;
    final QueryApplicationUseCase query;
    final ManageApplicationUseCase manage;
    final ManageCredentialsUseCase creds;
    final ManageApplicationDomainsUseCase domains;

    ApiClientFixture() {
        this(new ApplicationPolicy(20, Duration.ofDays(365), Duration.ofDays(730)));
    }

    ApiClientFixture(ApplicationPolicy policy) {
        this.policy = policy;
        when(tenants.findByIdForUpdate(any())).thenAnswer(i -> Optional.of(Tenant.restore(i.getArgument(0), "shop",
                "Shop", null, TenantStatus.ACTIVE, START)));
        ManageApplicationOriginsUseCase origins = new ManageApplicationOriginsUseCase(tenants, originRows, publisher,
                tx, StorefrontOriginPolicy.defaults());
        create = new CreateApplicationUseCase(applications, history, publisher, t -> contract, tx, policy, random,
                clock);
        query = new QueryApplicationUseCase(applications, credentials, history, clock);
        manage = new ManageApplicationUseCase(applications, credentials, history, origins, publisher, tx, random,
                clock);
        creds = new ManageCredentialsUseCase(applications, credentials, history, publisher, tx, policy, random, clock);
        domains = new ManageApplicationDomainsUseCase(applications, origins, history, publisher, tx, clock);
    }

    static final class FakeApplications implements ApplicationRepository {
        final Map<UUID, Application> rows = new LinkedHashMap<>();

        @Override
        public Application save(Application a) {
            rows.put(a.id(), a);
            return a;
        }

        @Override
        public Optional<Application> findVisibleById(UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(a -> a.status() != ApplicationStatus.DELETED);
        }

        @Override
        public Optional<Application> findByIdAndTenant(UUID id, UUID tenantId) {
            return Optional.ofNullable(rows.get(id))
                    .filter(a -> a.tenantId().equals(tenantId) && a.status() != ApplicationStatus.DELETED);
        }

        @Override
        public Optional<Application> findByIdAndTenantForUpdate(UUID id, UUID tenantId) {
            return findByIdAndTenant(id, tenantId);
        }

        @Override
        public List<Application> findByTenant(UUID tenantId) {
            return rows.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId) && a.status() != ApplicationStatus.DELETED)
                    .sorted(Comparator.comparing(Application::createdAt)).toList();
        }

        @Override
        public int lockAndCountByTenant(UUID tenantId) {
            return findByTenant(tenantId).size();
        }

        @Override
        public boolean existsName(UUID tenantId, String name, UUID excludeId) {
            return findByTenant(tenantId).stream().anyMatch(a -> a.name().equalsIgnoreCase(name)
                    && (excludeId == null || !a.id().equals(excludeId)));
        }

        @Override
        public boolean existsSiteKey(String siteKey) {
            return rows.values().stream().anyMatch(a -> siteKey.equals(a.siteKey()));
        }

        Application only(ApplicationKind kind) {
            return rows.values().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
        }
    }

    static final class FakeCredentials implements ApplicationCredentialRepository {
        final Map<UUID, ApplicationCredential> rows = new LinkedHashMap<>();

        @Override
        public ApplicationCredential save(ApplicationCredential c) {
            rows.put(c.id(), c);
            return c;
        }

        @Override
        public void touchLastUsed(UUID credentialId, java.time.Instant at) {
            ApplicationCredential c = rows.get(credentialId);
            if (c != null && c.status() == lms.identity.apiclient.domain.CredentialStatus.ACTIVE) {
                rows.put(credentialId, ApplicationCredential.restore(c.id(), c.applicationId(), c.tenantId(),
                        c.secretHash(), c.secretHint(), c.status(), c.createdAt(), c.createdBy(), c.expiresAt(), at,
                        c.revokedAt(), c.revokedBy(), c.revokeReason()));
            }
        }

        @Override
        public List<ApplicationCredential> findByApplication(UUID applicationId) {
            return rows.values().stream().filter(c -> c.applicationId().equals(applicationId)).toList();
        }

        @Override
        public List<ApplicationCredential> findByApplications(Collection<UUID> ids) {
            return rows.values().stream().filter(c -> ids.contains(c.applicationId())).toList();
        }

        @Override
        public Optional<ApplicationCredential> findByIdAndApplication(UUID credentialId, UUID applicationId) {
            return Optional.ofNullable(rows.get(credentialId)).filter(c -> c.applicationId().equals(applicationId));
        }
    }

    static final class FakeHistory implements ApplicationHistoryRepository {
        final List<ApplicationHistoryEntry> rows = new ArrayList<>();

        @Override
        public void append(ApplicationHistoryEntry entry) {
            rows.add(entry);
        }

        @Override
        public List<ApplicationHistoryEntry> findByApplication(UUID tenantId, UUID applicationId, int limit) {
            List<ApplicationHistoryEntry> mine = new ArrayList<>(rows.stream()
                    .filter(h -> h.tenantId().equals(tenantId) && h.applicationId().equals(applicationId)).toList());
            java.util.Collections.reverse(mine);
            return mine.stream().limit(limit).toList();
        }
    }

    /** Kho origin trong bộ nhớ, mô phỏng UNIQUE(origin) toàn hệ thống. */
    static final class FakeOrigins implements StorefrontOriginRepository {
        final Map<UUID, StorefrontOriginEntry> rows = new LinkedHashMap<>();
        final List<StorefrontOriginHistoryEntry> history = new ArrayList<>();

        @Override
        public List<StorefrontOriginEntry> findByTenant(UUID tenantId) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId))
                    .sorted(Comparator.comparing(StorefrontOriginEntry::createdAt)).toList();
        }

        @Override
        public List<String> findEnabledOrigins(UUID tenantId) {
            return findByTenant(tenantId).stream().filter(StorefrontOriginEntry::enabled)
                    .map(StorefrontOriginEntry::origin).toList();
        }

        @Override
        public Optional<UUID> findOwnerTenant(String origin) {
            return rows.values().stream().filter(e -> e.origin().equals(origin)).map(StorefrontOriginEntry::tenantId)
                    .findFirst();
        }

        @Override
        public void insert(StorefrontOriginEntry entry) {
            if (findOwnerTenant(entry.origin()).isPresent()) throw new StorefrontOriginDuplicatedException("trùng", null);
            rows.put(entry.id(), entry);
        }

        @Override
        public void update(StorefrontOriginEntry entry) {
            rows.put(entry.id(), entry);
        }

        @Override
        public void delete(UUID originId) {
            rows.remove(originId);
        }

        @Override
        public void appendHistory(StorefrontOriginHistoryEntry entry) {
            history.add(entry);
        }
    }

    static ApplicationException.Reason reasonOf(Throwable t) {
        return ((ApplicationException) t).reason();
    }

    static Reason notFound() {
        return Reason.APPLICATION_NOT_FOUND;
    }
}
