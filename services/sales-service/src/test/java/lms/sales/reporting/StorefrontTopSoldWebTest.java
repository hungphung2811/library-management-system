package lms.sales.reporting;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.storefrontService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.reporting.application.SalesStatsQueryPort.SoldTitle;
import lms.sales.reporting.application.StorefrontTopSoldUseCase;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.infrastructure.web.StorefrontTopSoldController;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** GET /internal/sales/storefront/top-sold: token dịch vụ vào được, chỉ lộ titleId/quantity, days/limit lạ bị chặn. */
@WebMvcTest(StorefrontTopSoldController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class, StorefrontTopSoldWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class StorefrontTopSoldWebTest {

    private static final String URL = "/internal/sales/storefront/top-sold";

    @TestConfiguration
    static class Beans {
        static final ReportingFakes.StatsQuery QUERY = new ReportingFakes.StatsQuery();
        static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T05:00:00Z"), ZoneOffset.UTC);

        @Bean
        StorefrontTopSoldUseCase storefrontTopSoldUseCase() {
            return new StorefrontTopSoldUseCase(QUERY, CLOCK);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @BeforeEach
    void reset() {
        Beans.QUERY.sold = new java.util.ArrayList<>();
        Beans.QUERY.lastTopRange = null;
        Beans.QUERY.lastTopLimit = 0;
    }

    @Test
    void tokenDichVuGoiDuocVaChiTraTitleIdVaQuantity() throws Exception {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        Beans.QUERY.sold.add(new SoldTitle(c, "Ten Bi Mat", 7, 999_000));
        Beans.QUERY.sold.add(new SoldTitle(b, "Ten B", 7, 1));
        Beans.QUERY.sold.add(new SoldTitle(a, "Ten A", 9, 2));

        String body = mvc.perform(get(URL).with(storefrontService(TENANT, List.of("SALES"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].titleId").value(a.toString()))
                .andExpect(jsonPath("$[0].quantity").value(9))
                .andExpect(jsonPath("$[1].titleId").value(b.toString()))
                .andExpect(jsonPath("$[2].titleId").value(c.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("titleName").doesNotContain("Bi Mat").doesNotContain("revenue")
                .doesNotContain("tenant");
        // mặc định 90 ngày tính cả hôm nay (17/11/2026 giờ VN), limit 100
        assertThat(Beans.QUERY.lastTopLimit).isEqualTo(100);
        assertThat(Beans.QUERY.lastTopRange).isEqualTo(
                new ReportRange(LocalDate.of(2026, 11, 17).minusDays(89), LocalDate.of(2026, 11, 17)));
    }

    @Test
    void daysVaLimitLaDuocChanKhongLoi() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        mvc.perform(get(URL + "?days=0&limit=0").with(svc)).andExpect(status().isOk());
        assertThat(Beans.QUERY.lastTopLimit).isEqualTo(1);
        assertThat(Beans.QUERY.lastTopRange.days()).isEqualTo(1);

        mvc.perform(get(URL + "?days=-5&limit=-1").with(svc)).andExpect(status().isOk());
        assertThat(Beans.QUERY.lastTopLimit).isEqualTo(1);
        assertThat(Beans.QUERY.lastTopRange.days()).isEqualTo(1);

        mvc.perform(get(URL + "?days=100000&limit=100000").with(svc)).andExpect(status().isOk());
        assertThat(Beans.QUERY.lastTopLimit).isEqualTo(100);
        assertThat(Beans.QUERY.lastTopRange.days()).isEqualTo(365);

        mvc.perform(get(URL + "?days=30&limit=5").with(svc)).andExpect(status().isOk());
        assertThat(Beans.QUERY.lastTopLimit).isEqualTo(5);
        assertThat(Beans.QUERY.lastTopRange.days()).isEqualTo(30);
    }

    @Test
    void thieuModuleHoacTenantBiChan() throws Exception {
        mvc.perform(get(URL).with(storefrontService(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get(URL).with(storefrontService(null, List.of("SALES"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }
}
