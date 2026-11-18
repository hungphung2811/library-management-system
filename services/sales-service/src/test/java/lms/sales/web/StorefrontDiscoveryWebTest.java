package lms.sales.web;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.storefrontService;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.infrastructure.web.StorefrontController;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-0019 B2 qua HTTP: sort/inStock cho danh sách, endpoint gợi ý, mã lỗi validate, khóa quyền. */
@WebMvcTest(StorefrontController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class StorefrontDiscoveryWebTest {

    private static final String B = "/internal/sales/storefront";

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private UUID book(String name, long cover, long stock) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tac gia", "ISBN-SECRET-" + name,
                BigDecimal.valueOf(cover), "Mo ta", false));
        h.registerItem.execute(TENANT, lms.sales.support.WebTokens.USER, id, null, null);
        if (stock > 0) {
            h.adjust.execute(TENANT, lms.sales.support.WebTokens.USER, id, stock, "ton dau");
        }
        return id;
    }

    @Test
    void sortVaInStockLocDanhSach() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        book("WebRe", 10_000, 2);
        book("WebDat", 90_000, 0);
        book("WebGiua", 50_000, 1);

        mvc.perform(get(B + "/items?keyword=Web&sort=price_asc").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("WebRe"))
                .andExpect(jsonPath("$.items[2].name").value("WebDat"));
        mvc.perform(get(B + "/items?keyword=Web&sort=price_desc").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("WebDat"));
        mvc.perform(get(B + "/items?keyword=Web&sort=newest&inStock=true").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").value("WebGiua"));
        mvc.perform(get(B + "/items?keyword=Web&inStock=false").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
        // không từ khóa + price_asc dùng nhánh DB
        mvc.perform(get(B + "/items?sort=price_asc&size=50").with(svc)).andExpect(status().isOk());
    }

    @Test
    void sortNgoaiAllowlistLaInvalidRequestKhongChamDb() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        int before = h.storefrontListCalls;
        for (String bad : new String[] {"bogus", "NEWEST", "price_asc%3Bdrop", "name_asc", "%20"}) {
            mvc.perform(get(B + "/items?sort=" + bad).with(svc)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(h.storefrontListCalls).isEqualTo(before);
        mvc.perform(get(B + "/items?inStock=notabool").with(svc)).andExpect(status().isBadRequest());
    }

    @Test
    void goiYTraBaTruongVaKhongLoGiaTonIsbn() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        UUID id = book("Gợi Ý Một", 10_000, 3);
        book("Gợi Ý Hai", 20_000, 0);

        String body = mvc.perform(get(B + "/suggestions").param("q", "gợi ý").param("limit", "1").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].titleId").exists()).andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].author").value("Tac gia"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("price").doesNotContain("inStock").doesNotContain("ISBN-SECRET")
                .doesNotContain("onHand").doesNotContain("tenant");
        mvc.perform(get(B + "/suggestions").param("q", "gợi ý").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(B + "/suggestions?q=khong-co-sach-nay").with(svc)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(id).isNotNull();
    }

    @Test
    void goiYQNganDaiHoacThieuLaInvalidRequest() throws Exception {
        var svc = storefrontService(TENANT, List.of("SALES"));
        mvc.perform(get(B + "/suggestions").with(svc)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(B + "/suggestions?q=a").with(svc)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(B + "/suggestions?q=" + "x".repeat(51)).with(svc)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void goiYCanQuyenDungKhoaVaToKenDichVuVaoDuoc() throws Exception {
        // token dịch vụ (@StorefrontAccessible ở mức lớp) vào được; thiếu module/tenant bị chặn như endpoint khác
        mvc.perform(get(B + "/suggestions?q=ab").with(storefrontService(TENANT, List.of("SALES"))))
                .andExpect(status().isOk());
        mvc.perform(get(B + "/suggestions?q=ab").with(storefrontService(TENANT, List.of("LENDING"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        mvc.perform(get(B + "/suggestions?q=ab").with(storefrontService(null, List.of("SALES"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        // người dùng thường: khóa list không đủ, cần đúng StorefrontController/suggestions
        mvc.perform(get(B + "/suggestions?q=ab").with(withKey("StorefrontController/list")))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/suggestions?q=ab").with(withKeys(TENANT, List.of("SALES"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(B + "/suggestions?q=ab").with(withKey("StorefrontController/suggestions")))
                .andExpect(status().isOk());
    }
}
