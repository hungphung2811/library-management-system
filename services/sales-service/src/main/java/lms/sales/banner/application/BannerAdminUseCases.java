package lms.sales.banner.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.banner.domain.Banner;
import lms.sales.banner.domain.BannerLimits;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị banner: list/get/create/update/delete. Id của tenant khác hoặc không có: 404 BANNER_NOT_FOUND. Ghi audit
 * cùng transaction (data chỉ có bannerId).
 */
public class BannerAdminUseCases {

    /** Nội dung lưu (create và update); sortOrder/active null thành mặc định (0, true). */
    public record SaveCommand(String title, String subtitle, String linkPath, Integer sortOrder, Boolean active,
                              Instant startsAt, Instant endsAt) {
    }

    private final BannerRepository repository;
    private final SalesAuditPort audit;
    private final Clock clock;

    public BannerAdminUseCases(BannerRepository repository, SalesAuditPort audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BannerAdminView> list(UUID tenantId) {
        Instant now = clock.instant();
        return repository.findAll(tenantId).stream().map(b -> BannerAdminView.from(b, now)).toList();
    }

    @Transactional(readOnly = true)
    public BannerAdminView get(UUID tenantId, UUID id) {
        return BannerAdminView.from(repository.findById(tenantId, id).orElseThrow(Banner::notFound), clock.instant());
    }

    /** Vượt 20 banner: 409 BANNER_LIMIT_EXCEEDED (đếm rồi chèn, không khóa: chấp nhận vượt nhẹ khi đồng thời). */
    @Transactional
    public BannerAdminView create(UUID tenantId, UUID actorId, SaveCommand c) {
        Instant now = clock.instant();
        Banner banner = Banner.create(UUID.randomUUID(), tenantId, c.title(), c.subtitle(), c.linkPath(),
                c.sortOrder(), c.active(), c.startsAt(), c.endsAt(), actorId, now);
        if (!BannerLimits.canCreate(repository.count(tenantId))) {
            throw Banner.limitExceeded();
        }
        Banner saved = repository.insert(banner);
        record(tenantId, actorId, SalesAuditPort.Actions.BANNER_CREATED, saved.id(), now);
        return BannerAdminView.from(saved, now);
    }

    /** version lệch: 409 BANNER_VERSION_CONFLICT. */
    @Transactional
    public BannerAdminView update(UUID tenantId, UUID actorId, UUID id, long version, SaveCommand c) {
        Banner current = repository.findById(tenantId, id).orElseThrow(Banner::notFound);
        if (current.version() != version) {
            throw Banner.versionConflict();
        }
        Instant now = clock.instant();
        Banner next = current.replaceWith(c.title(), c.subtitle(), c.linkPath(), c.sortOrder(), c.active(),
                c.startsAt(), c.endsAt(), actorId, now);
        Banner saved = repository.update(next);
        record(tenantId, actorId, SalesAuditPort.Actions.BANNER_UPDATED, id, now);
        return BannerAdminView.from(saved, now);
    }

    @Transactional
    public void delete(UUID tenantId, UUID actorId, UUID id) {
        if (!repository.delete(tenantId, id)) {
            throw Banner.notFound();
        }
        record(tenantId, actorId, SalesAuditPort.Actions.BANNER_DELETED, id, clock.instant());
    }

    private void record(UUID tenantId, UUID actorId, String action, UUID id, Instant now) {
        audit.record(new SalesAuditPort.AuditEntry(tenantId, action, "Banner", id, actorId, now,
                Map.of("bannerId", id.toString())));
    }
}
