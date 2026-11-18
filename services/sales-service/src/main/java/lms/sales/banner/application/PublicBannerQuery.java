package lms.sales.banner.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lms.sales.banner.domain.BannerLimits;
import org.springframework.transaction.annotation.Transactional;

/** Banner đang hiệu lực cho khách (tối đa 10, theo sortOrder). Không gọi catalog. */
public class PublicBannerQuery {

    private final BannerRepository repository;
    private final Clock clock;

    public PublicBannerQuery(BannerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PublicBannerView> live(UUID tenantId) {
        return repository.findLive(tenantId, clock.instant(), BannerLimits.MAX_PUBLIC).stream()
                .map(PublicBannerView::from).toList();
    }
}
