package lms.sales.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.RefundController;
import lms.sales.ordering.infrastructure.web.SalesAuditController;
import lms.sales.ordering.infrastructure.web.SalesOrderController;
import lms.sales.ordering.infrastructure.web.SalesReturnController;
import lms.sales.ordering.infrastructure.web.ShopController;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.shared.application.PageResult;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.ListStockItemsUseCase;
import lms.sales.stock.infrastructure.web.GoodsReceiptController;
import lms.sales.stock.infrastructure.web.StockAdjustmentController;
import lms.sales.stock.infrastructure.web.StockItemController;
import lms.sales.stock.infrastructure.web.StorefrontController;
import lms.sales.stock.infrastructure.web.SupplierController;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Token integration (aud lms-integration, pha I3) trên mọi endpoint sales-service: đúng bốn endpoint đọc có
 * {@code @IntegrationScope} (stock-items search/get = inventory:read, orders search/get = orders:read) đi qua được;
 * mọi endpoint còn lại, kể cả contact/notes/receipt (PII), ghi, storefront, đều 403 FORBIDDEN dù token có đủ scope.
 * Thiếu scope, thiếu module SALES, thiếu tenant đều 403; tenant của use case là tenant trong claim token.
 */
@WebMvcTest({SalesSettingsController.class, StockItemController.class, StockAdjustmentController.class,
        SupplierController.class, GoodsReceiptController.class, PosOrderController.class, SalesOrderController.class,
        ShopController.class, StorefrontController.class, SalesReturnController.class, RefundController.class,
        SalesAuditController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesIntegrationScopeWebTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private static final Set<String> STOCK_READ = Set.of("StockItemController/search", "StockItemController/get");
    private static final Set<String> ORDER_READ = Set.of("SalesOrderController/search", "SalesOrderController/get");

    @Autowired MockMvc mvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    /** Thay use case thật bằng bản giả để quan sát tenant mà controller truyền vào. */
    @MockitoBean ListStockItemsUseCase listStock;
    @MockitoBean GetStockItemUseCase getStock;
    @MockitoBean OrderQueryUseCases orderQueries;

    private static RequestPostProcessor integration(UUID tenant, List<String> modules, String scope) {
        return jwt().jwt(j -> {
            j.audience(List.of("lms-integration")).subject("app").claim("scope", scope)
                    .claim("application_id", UUID.randomUUID().toString());
            if (tenant != null) j.claim("tenantId", tenant.toString());
            if (modules != null) j.claim("modules", modules);
        });
    }

    private static RequestPostProcessor allRead() {
        return integration(TENANT, List.of("SALES"), "books:read inventory:read orders:read");
    }

    private int call(SalesPermissionMatrixWebTest.Endpoint e, RequestPostProcessor token) throws Exception {
        var req = request(e.method(), e.url()).with(token);
        if (e.body() != null) req.contentType(MediaType.APPLICATION_JSON).content(e.body());
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private void stubReads() {
        when(listStock.execute(any(), anyBoolean(), anyInt(), anyInt())).thenReturn(new PageResult<>(List.of(), 0, 0, 20));
        when(orderQueries.search(any(), any())).thenReturn(new PageResult<>(List.of(), 0, 0, 20));
        when(getStock.execute(any(), any())).thenThrow(
                new lms.sales.shared.domain.SalesException(lms.sales.shared.domain.ErrorCode.STOCK_ITEM_NOT_FOUND, "x"));
        when(orderQueries.get(any(), any())).thenThrow(
                new lms.sales.shared.domain.SalesException(lms.sales.shared.domain.ErrorCode.ORDER_NOT_FOUND, "x"));
    }

    @Test
    void chiBonEndpointDocMoChoTokenIntegrationConLaiLa403() throws Exception {
        stubReads();
        Set<String> open = new java.util.HashSet<>(STOCK_READ);
        open.addAll(ORDER_READ);
        Set<String> seenOpen = new java.util.HashSet<>();
        for (var e : SalesPermissionMatrixWebTest.ENDPOINTS) {
            int status = call(e, allRead());
            if (open.contains(e.key())) {
                seenOpen.add(e.key());
                assertThat(status).as(e.key()).isNotIn(401, 403);
            } else {
                assertThat(status).as(e.key() + " (không gắn @IntegrationScope) phải 403").isEqualTo(403);
            }
        }
        assertThat(seenOpen).as("ma trận phải chứa đủ bốn endpoint đọc").isEqualTo(open);
    }

    @Test
    void endpointLoCoPiiVaNoiBoLa403KemMaFORBIDDENKhongLoKhoa() throws Exception {
        UUID id = UUID.randomUUID();
        for (String url : List.of("/internal/sales/orders/" + id + "/contact", "/internal/sales/orders/" + id + "/notes",
                "/internal/sales/orders/" + id + "/receipt", "/internal/sales/stock-items/" + id + "/details",
                "/internal/sales/stock-items/" + id + "/ledger", "/internal/sales/suppliers",
                "/internal/sales/audit-log", "/internal/sales/settings")) {
            String body = mvc.perform(get(url).with(allRead())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN")).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Controller/");
        }
    }

    @Test
    void sacScopeSaiBi403InsufficientScope() throws Exception {
        stubReads();
        UUID id = UUID.randomUUID();
        RequestPostProcessor onlyOrders = integration(TENANT, List.of("SALES"), "orders:read books:read");
        RequestPostProcessor onlyInventory = integration(TENANT, List.of("SALES"), "inventory:read books:read");
        for (String url : List.of("/internal/sales/stock-items", "/internal/sales/stock-items/" + id)) {
            mvc.perform(get(url).with(onlyOrders)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
            mvc.perform(get(url).with(integration(TENANT, List.of("SALES"), "inventory:write")))
                    .andExpect(status().isForbidden());
        }
        for (String url : List.of("/internal/sales/orders", "/internal/sales/orders/" + id)) {
            mvc.perform(get(url).with(onlyInventory)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
        }
        mvc.perform(get("/internal/sales/orders").with(integration(TENANT, List.of("SALES"), "")))
                .andExpect(status().isForbidden());
    }

    @Test
    void khongCoModuleSalesBi403ModuleNotEnabled() throws Exception {
        for (String url : List.of("/internal/sales/stock-items", "/internal/sales/orders")) {
            for (List<String> modules : new List[] {List.of("LENDING"), List.<String>of(), null}) {
                mvc.perform(get(url).with(integration(TENANT, modules, "inventory:read orders:read")))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            }
        }
    }

    @Test
    void khongCoTenantBi403TenantRequired() throws Exception {
        for (String url : List.of("/internal/sales/stock-items", "/internal/sales/orders")) {
            mvc.perform(get(url).with(integration(null, List.of("SALES"), "inventory:read orders:read")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    @Test
    void tenantCuaUseCaseLaTenantTrongTokenKhongPhaiThamSoHayHeader() throws Exception {
        stubReads();
        UUID id = UUID.randomUUID();
        mvc.perform(get("/internal/sales/stock-items?tenantId=" + OTHER_TENANT)
                        .header("X-Tenant-Id", OTHER_TENANT.toString())
                        .with(integration(TENANT, List.of("SALES"), "inventory:read")))
                .andExpect(status().isOk());
        verify(listStock).execute(org.mockito.ArgumentMatchers.eq(TENANT), anyBoolean(), anyInt(), anyInt());
        mvc.perform(get("/internal/sales/stock-items/" + id).header("X-Tenant-Id", OTHER_TENANT.toString())
                .with(integration(TENANT, List.of("SALES"), "inventory:read")));
        verify(getStock).execute(TENANT, id);
        mvc.perform(get("/internal/sales/orders/" + id + "?tenantId=" + OTHER_TENANT)
                .header("X-Tenant-Id", OTHER_TENANT.toString())
                .with(integration(TENANT, List.of("SALES"), "orders:read")));
        verify(orderQueries).get(TENANT, id);
    }
}
