package lms.sales.banner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.banner.application.BannerAdminUseCases;
import lms.sales.banner.application.BannerRepository;
import lms.sales.banner.application.PublicBannerQuery;
import lms.sales.banner.domain.Banner;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/** Bộ giả trong bộ nhớ cho test banner (không Spring): repository theo hợp đồng port, audit, đồng hồ chỉnh được. */
public final class BannerFixture {

    public static final Instant T0 = Instant.parse("2026-11-17T03:00:00Z");

    public final UUID tenant = UUID.randomUUID();
    public final UUID staff = UUID.randomUUID();
    public final Map<UUID, Banner> rows = new LinkedHashMap<>();
    public final List<SalesAuditPort.AuditEntry> audits = new ArrayList<>();
    public Instant now = T0;
    public final Clock clock = new Clock() {
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
    };
    public final BannerRepository repository = new InMemoryRepository();
    public final BannerAdminUseCases admin = new BannerAdminUseCases(repository, audits::add, clock);
    public final PublicBannerQuery publicQuery = new PublicBannerQuery(repository, clock);

    public static BannerAdminUseCases.SaveCommand cmd(String title) {
        return new BannerAdminUseCases.SaveCommand(title, null, null, null, null, null, null);
    }

    public static ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (SalesException e) {
            return e.code();
        }
        throw new AssertionError("Không có SalesException");
    }

    private final class InMemoryRepository implements BannerRepository {
        @Override
        public Banner insert(Banner b) {
            rows.put(b.id(), b);
            return b;
        }

        @Override
        public Banner update(Banner b) {
            Banner old = rows.get(b.id());
            if (old == null) {
                throw Banner.notFound();
            }
            if (old.version() != b.version()) {
                throw Banner.versionConflict();
            }
            Banner saved = Banner.restore(b.id(), b.tenantId(), b.title(), b.subtitle(), b.linkPath(), b.sortOrder(),
                    b.active(), b.startsAt(), b.endsAt(), b.version() + 1, b.createdAt(), b.updatedAt(),
                    b.createdBy(), b.updatedBy());
            rows.put(saved.id(), saved);
            return saved;
        }

        @Override
        public Optional<Banner> findById(UUID tenantId, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(b -> b.tenantId().equals(tenantId));
        }

        private List<Banner> sorted(UUID tenantId) {
            return rows.values().stream().filter(b -> b.tenantId().equals(tenantId))
                    .sorted(Comparator.comparingInt(Banner::sortOrder).thenComparing(Banner::createdAt)
                            .thenComparing(Banner::id)).toList();
        }

        @Override
        public List<Banner> findAll(UUID tenantId) {
            return sorted(tenantId);
        }

        @Override
        public long count(UUID tenantId) {
            return sorted(tenantId).size();
        }

        @Override
        public boolean delete(UUID tenantId, UUID id) {
            Banner b = rows.get(id);
            if (b == null || !b.tenantId().equals(tenantId)) {
                return false;
            }
            rows.remove(id);
            return true;
        }

        @Override
        public List<Banner> findLive(UUID tenantId, Instant at, int limit) {
            return sorted(tenantId).stream().filter(b -> b.isLiveAt(at)).limit(limit).toList();
        }
    }
}
