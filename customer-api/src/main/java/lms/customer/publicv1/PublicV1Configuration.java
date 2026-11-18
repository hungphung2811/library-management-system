package lms.customer.publicv1;

import lms.customer.storefront.StorefrontAccessCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Nhóm Public API v1 (Public Site Key, SaaS API Authentication P2). Controller mỏng gọi lại client/DTO của nhóm khác. */
@Configuration
public class PublicV1Configuration {

    @Bean
    SiteKeyAccess siteKeyAccess(StorefrontAccessCache storefronts) {
        return new SiteKeyAccess(storefronts);
    }
}
