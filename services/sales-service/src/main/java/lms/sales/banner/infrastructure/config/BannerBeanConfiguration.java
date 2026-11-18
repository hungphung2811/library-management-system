package lms.sales.banner.infrastructure.config;

import java.time.Clock;
import lms.sales.banner.application.BannerAdminUseCases;
import lms.sales.banner.application.BannerRepository;
import lms.sales.banner.application.PublicBannerQuery;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context banner (ADR-0019). */
@Configuration
public class BannerBeanConfiguration {

    @Bean
    BannerAdminUseCases bannerAdminUseCases(BannerRepository repository, SalesAuditPort audit, Clock clock) {
        return new BannerAdminUseCases(repository, audit, clock);
    }

    @Bean
    PublicBannerQuery publicBannerQuery(BannerRepository repository, Clock clock) {
        return new PublicBannerQuery(repository, clock);
    }
}
