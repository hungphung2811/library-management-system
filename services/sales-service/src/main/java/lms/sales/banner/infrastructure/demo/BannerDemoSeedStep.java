package lms.sales.banner.infrastructure.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.banner.application.BannerRepository;
import lms.sales.banner.domain.Banner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed banner trang chủ cửa hàng của tenant demo (ADR-0018, ADR-0019): đủ bốn trạng thái hiển thị (LIVE, SCHEDULED,
 * EXPIRED, INACTIVE) để màn quản lý banner và storefront có dữ liệu. Idempotent theo id tất định
 * {@code DemoIds.of("banner", n)}: banner đã có thì bỏ qua (không ghi đè phần người dùng đã sửa).
 */
@Component
@Profile("demo-seed")
public class BannerDemoSeedStep implements DemoSeedStep {

    /** title, subtitle, đường dẫn, thứ tự, active, startsAt (ngày so với hiện tại, null = không), endsAt. */
    private record Spec(String title, String subtitle, String linkPath, int sortOrder, boolean active,
                        Duration startsOffset, Duration endsOffset) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("Sách mới tháng này", "Hàng trăm đầu sách vừa về kho", "/books?sort=newest", 1, true, null, null),
            new Spec("Combo bộ sách giảm 15%", "Áp dụng cho các bộ sách trọn bộ", "/books?series=all", 2, true,
                    Duration.ofDays(-10), Duration.ofDays(20)),
            new Spec("Khuyến mãi mùa tựu trường", "Sắp diễn ra, đã lên lịch", "/books?category=giao-trinh", 3, true,
                    Duration.ofDays(7), Duration.ofDays(30)),
            new Spec("Tuần lễ sách Việt Nam", "Chương trình đã kết thúc", "/books", 4, true,
                    Duration.ofDays(-60), Duration.ofDays(-30)),
            new Spec("Banner tạm ẩn", "Bản nháp chưa công bố", null, 5, false, null, null));

    private final BannerRepository banners;
    private final TransactionOperations tx;

    public BannerDemoSeedStep(BannerRepository banners, TransactionOperations tx) {
        this.banners = banners;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String name() {
        return "sales.banner";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        UUID tenantId = requireTenant(context);
        Instant now = context.clock().instant();
        UUID actor = context.plan().staff().get(2).id();
        int created = 0;
        int skipped = 0;
        for (int i = 0; i < SPECS.size(); i++) {
            Spec s = SPECS.get(i);
            UUID id = DemoIds.of("banner", i + 1);
            if (banners.findById(tenantId, id).isPresent()) {
                skipped++;
                continue;
            }
            Banner banner = Banner.create(id, tenantId, s.title(), s.subtitle(), s.linkPath(), s.sortOrder(),
                    s.active(), s.startsOffset() == null ? null : now.plus(s.startsOffset()),
                    s.endsOffset() == null ? null : now.plus(s.endsOffset()), actor, now);
            tx.executeWithoutResult(status -> banners.insert(banner));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    private static UUID requireTenant(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        return context.tenantId();
    }
}
