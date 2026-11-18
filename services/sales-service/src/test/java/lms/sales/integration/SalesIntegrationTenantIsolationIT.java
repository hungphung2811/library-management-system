package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cách ly tenant cho token INTEGRATION (pha I3) trên PostgreSQL thật, chạy bằng vai trò sales_app (RLS): tenant lấy từ
 * claim của token, không từ tham số {@code tenantId} hay header {@code X-Tenant-Id}. Application của tenant B không đọc
 * được tồn kho/đơn của tenant A; application của A đọc được. Cần Docker:
 * {@code mvn -o -pl services/sales-service -am test -Dtest=SalesIntegrationTenantIsolationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class SalesIntegrationTenantIsolationIT {

    private static final String B = "/internal/sales";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    static final Map<UUID, CatalogLookupPort.CatalogTitle> TITLES = new ConcurrentHashMap<>();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RegisterStockItemUseCase registerItem;
    @Autowired AdjustStockUseCase adjust;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean CatalogLookupPort catalog;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @BeforeEach
    void stubCatalog() {
        when(catalog.lookup(any())).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            Map<UUID, CatalogLookupPort.CatalogTitle> out = new java.util.HashMap<>();
            ids.forEach(id -> {
                if (TITLES.containsKey(id)) out.put(id, TITLES.get(id));
            });
            return out;
        });
    }

    private RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(user.toString()).claim("admin", true).claim("modules", List.of("SALES"))
                .claim("tenantId", tenant.toString()));
    }

    private RequestPostProcessor integration(UUID tenant) {
        return jwt().jwt(j -> j.audience(List.of("lms-integration")).subject("app")
                .claim("application_id", UUID.randomUUID().toString()).claim("tenantId", tenant.toString())
                .claim("modules", List.of("SALES")).claim("scope", "inventory:read orders:read"));
    }

    @Test
    void applicationCuaBKhongDocDuocTonKhoVaDonCuaATheoToken() throws Exception {
        UUID title = UUID.randomUUID();
        TITLES.put(title, new CatalogLookupPort.CatalogTitle(title, "Sach rieng cua A", "Tac gia",
                "ISBN-" + title.toString().substring(0, 8), BigDecimal.valueOf(100_000)));
        TenantContext.runAs(tenantA, () -> {
            registerItem.execute(tenantA, user, title, null, null);
            adjust.execute(tenantA, user, title, 10, "ton dau");
        });
        JsonNode created = json.readTree(mvc.perform(post(B + "/pos-orders").with(admin(tenantA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + title + "\",\"quantity\":2}],\"paymentMethod\":\"CASH\","
                                + "\"walkIn\":{\"name\":\"Tran Thi Bich\",\"phone\":\"0987654321\"},"
                                + "\"clientRequestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String order = created.get("id").asText();

        // Application của A đọc được
        mvc.perform(get(B + "/stock-items/" + title).with(integration(tenantA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.titleId").value(title.toString()));
        mvc.perform(get(B + "/stock-items").with(integration(tenantA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(B + "/orders/" + order).with(integration(tenantA))).andExpect(status().isOk());
        mvc.perform(get(B + "/orders").with(integration(tenantA))).andExpect(jsonPath("$.total").value(1));

        // Application của B (kể cả khi gửi tenantId/X-Tenant-Id của A) không thấy gì của A
        mvc.perform(get(B + "/stock-items?tenantId=" + tenantA).header("X-Tenant-Id", tenantA.toString())
                        .with(integration(tenantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(B + "/stock-items/" + title).header("X-Tenant-Id", tenantA.toString())
                .with(integration(tenantB))).andExpect(status().isNotFound());
        mvc.perform(get(B + "/orders?tenantId=" + tenantA).header("X-Tenant-Id", tenantA.toString())
                        .with(integration(tenantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(B + "/orders/" + order + "?tenantId=" + tenantA).header("X-Tenant-Id", tenantA.toString())
                .with(integration(tenantB))).andExpect(status().isNotFound());
        // PII và ghi vẫn đóng với token integration của chính tenant A
        mvc.perform(get(B + "/orders/" + order + "/contact").with(integration(tenantA))).andExpect(status().isForbidden());
        mvc.perform(post(B + "/stock-adjustments").with(integration(tenantA)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleId\":\"" + title + "\",\"delta\":-5,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(order).isNotBlank();
    }
}
