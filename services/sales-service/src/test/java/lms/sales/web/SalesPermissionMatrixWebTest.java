package lms.sales.web;

import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.noPermission;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.RefundController;
import lms.sales.ordering.infrastructure.web.SalesAuditController;
import lms.sales.ordering.infrastructure.web.SalesOrderController;
import lms.sales.ordering.infrastructure.web.SalesReturnController;
import lms.sales.ordering.infrastructure.web.ShopController;
import lms.sales.stock.infrastructure.web.StorefrontController;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.stock.infrastructure.web.GoodsReceiptController;
import lms.sales.stock.infrastructure.web.StockAdjustmentController;
import lms.sales.stock.infrastructure.web.StockItemController;
import lms.sales.stock.infrastructure.web.SupplierController;
import lms.sales.support.HarnessBeans;
import lms.sales.support.WebTokens;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Ma trận phân quyền của mọi endpoint sales-service: thiếu khóa 403, đúng khóa qua, khóa của endpoint khác 403,
 * token không có module SALES 403 MODULE_NOT_ENABLED (kể cả admin), và tập khóa thật khớp tập đã công bố cho B1.6.
 */
@WebMvcTest({SalesSettingsController.class, StockItemController.class, StockAdjustmentController.class,
        SupplierController.class, GoodsReceiptController.class, PosOrderController.class, SalesOrderController.class,
        ShopController.class, StorefrontController.class, SalesReturnController.class, RefundController.class,
        SalesAuditController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesPermissionMatrixWebTest {

    private static final String B = "/internal/sales";
    private static final String ID = UUID.randomUUID().toString();
    private static final String TITLE = UUID.randomUUID().toString();
    private static final String POS = "{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":1}],"
            + "\"paymentMethod\":\"CASH\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";

    private static final String SHOP = "{\"lines\":[{\"titleId\":\"" + TITLE + "\",\"quantity\":1}],"
            + "\"fulfilment\":\"PICKUP\",\"paymentMethod\":\"CASH\","
            + "\"recipient\":{\"name\":\"An\",\"phone\":\"0900000000\"},"
            + "\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
    private static final String RETURN = "{\"orderId\":\"" + ID + "\",\"lines\":[{\"orderLineId\":\"" + ID
            + "\",\"quantity\":1,\"disposition\":\"RESTOCK\"}],\"reason\":\"loi\","
            + "\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
    private static final String RETURN_LATE = RETURN.replace("\"reason\":\"loi\"",
            "\"reason\":\"loi\",\"lateReason\":\"quan ly dong y\"");

    record Endpoint(HttpMethod method, String url, String body, String key) {
    }

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, B + "/settings", null, "SalesSettingsController/get"),
            new Endpoint(HttpMethod.PUT, B + "/settings", "{\"vatRateBps\":500,\"pricesIncludeVat\":true,"
                    + "\"returnWindowDays\":7,\"fixedShippingFee\":0,\"lowStockThreshold\":5,"
                    + "\"walkInPiiRetentionMonths\":12,\"version\":0}", "SalesSettingsController/update"),
            new Endpoint(HttpMethod.POST, B + "/stock-items", "{\"titleId\":\"" + TITLE + "\"}",
                    "StockItemController/register"),
            new Endpoint(HttpMethod.GET, B + "/stock-items", null, "StockItemController/search"),
            new Endpoint(HttpMethod.GET, B + "/stock-items/" + ID, null, "StockItemController/get"),
            new Endpoint(HttpMethod.PUT, B + "/stock-items/" + ID + "/price", "{\"sellingPrice\":1000}",
                    "StockItemController/updatePrice"),
            new Endpoint(HttpMethod.GET, B + "/stock-items/" + ID + "/ledger", null, "StockItemController/ledger"),
            new Endpoint(HttpMethod.GET, B + "/stock-items/" + ID + "/details", null,
                    "StockItemController/getDetails"),
            new Endpoint(HttpMethod.PUT, B + "/stock-items/" + ID + "/details", "{\"sku\":\"LMS-1\"}",
                    "StockItemController/updateDetails"),
            new Endpoint(HttpMethod.POST, B + "/stock-adjustments",
                    "{\"titleId\":\"" + TITLE + "\",\"delta\":5,\"reason\":\"kiem\"}",
                    "StockAdjustmentController/adjust"),
            new Endpoint(HttpMethod.POST, B + "/stocktakes",
                    "{\"titleId\":\"" + TITLE + "\",\"countedOnHand\":5,\"reason\":\"kiem\"}",
                    "StockAdjustmentController/stocktake"),
            new Endpoint(HttpMethod.GET, B + "/suppliers", null, "SupplierController/search"),
            new Endpoint(HttpMethod.POST, B + "/suppliers", "{\"name\":\"NCC Matrix\"}", "SupplierController/create"),
            new Endpoint(HttpMethod.PUT, B + "/suppliers/" + ID, "{\"name\":\"NCC\"}", "SupplierController/update"),
            new Endpoint(HttpMethod.POST, B + "/goods-receipts", "{\"supplierId\":\"" + ID + "\",\"lines\":[{"
                    + "\"titleId\":\"" + TITLE + "\",\"quantity\":1,\"unitCost\":10}],\"clientRequestId\":\""
                    + UUID.randomUUID() + "\"}", "GoodsReceiptController/create"),
            new Endpoint(HttpMethod.GET, B + "/goods-receipts", null, "GoodsReceiptController/search"),
            new Endpoint(HttpMethod.GET, B + "/goods-receipts/" + ID, null, "GoodsReceiptController/get"),
            new Endpoint(HttpMethod.POST, B + "/goods-receipts/" + ID + "/confirmation", null,
                    "GoodsReceiptController/confirm"),
            new Endpoint(HttpMethod.POST, B + "/goods-receipts/" + ID + "/cancellation", null,
                    "GoodsReceiptController/cancel"),
            new Endpoint(HttpMethod.POST, B + "/pos-orders", POS, "PosOrderController/create"),
            new Endpoint(HttpMethod.POST, B + "/pos-orders/discounted", POS, "PosOrderController/createWithDiscount"),
            new Endpoint(HttpMethod.GET, B + "/orders", null, "SalesOrderController/search"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID, null, "SalesOrderController/get"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID + "/contact", null, "SalesOrderController/getContact"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID + "/notes", null, "SalesOrderController/getNotes"),
            new Endpoint(HttpMethod.PUT, B + "/orders/" + ID + "/notes", "{\"staffNote\":\"x\"}",
                    "SalesOrderController/updateNotes"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID + "/receipt", null, "SalesOrderController/receipt"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/confirmation", "{\"shippingFee\":0}",
                    "SalesOrderController/confirm"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/discount",
                    "{\"orderDiscount\":{\"type\":\"AMOUNT\",\"value\":1000}}",
                    "SalesOrderController/applyDiscount"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/payment", "{\"method\":\"CASH\"}",
                    "SalesOrderController/markPaid"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/ready-for-pickup", null,
                    "SalesOrderController/markReadyForPickup"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/shipping", null,
                    "SalesOrderController/markShipping"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/completion", null,
                    "SalesOrderController/complete"),
            new Endpoint(HttpMethod.POST, B + "/orders/" + ID + "/cancellation", "{\"reason\":\"x\"}",
                    "SalesOrderController/cancel"),
            new Endpoint(HttpMethod.POST, B + "/shop/orders", SHOP, "ShopController/placeOrder"),
            new Endpoint(HttpMethod.GET, B + "/shop/orders", null, "ShopController/myOrders"),
            new Endpoint(HttpMethod.GET, B + "/shop/orders/" + ID, null, "ShopController/myOrder"),
            new Endpoint(HttpMethod.POST, B + "/shop/orders/" + ID + "/cancellation", null,
                    "ShopController/cancelMyOrder"),
            new Endpoint(HttpMethod.GET, B + "/storefront/items", null, "StorefrontController/list"),
            new Endpoint(HttpMethod.GET, B + "/storefront/items/" + ID, null, "StorefrontController/get"),
            new Endpoint(HttpMethod.GET, B + "/storefront/items/" + ID + "/preview", null,
                    "StorefrontController/preview"),
            new Endpoint(HttpMethod.GET, B + "/storefront/suggestions?q=abc", null,
                    "StorefrontController/suggestions"),
            new Endpoint(HttpMethod.POST, B + "/returns", RETURN, "SalesReturnController/create"),
            new Endpoint(HttpMethod.POST, B + "/returns/late", RETURN_LATE, "SalesReturnController/createLate"),
            new Endpoint(HttpMethod.GET, B + "/returns/" + ID, null, "SalesReturnController/get"),
            new Endpoint(HttpMethod.GET, B + "/orders/" + ID + "/returns", null,
                    "SalesReturnController/listByOrder"),
            new Endpoint(HttpMethod.GET, B + "/refunds", null, "RefundController/search"),
            new Endpoint(HttpMethod.POST, B + "/refunds/" + ID + "/completion", "{\"method\":\"CASH\"}",
                    "RefundController/markRefunded"),
            new Endpoint(HttpMethod.GET, B + "/audit-log", null, "SalesAuditController/search"));

    @Autowired
    MockMvc mvc;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private int call(Endpoint e, RequestPostProcessor token) throws Exception {
        var req = request(e.method(), e.url()).with(token);
        if (e.body() != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url());
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void thieuKhoaBi403VoiMoiEndpoint() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url()).with(noPermission());
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void dungKhoaThiQuaVaKhoaCuaEndpointKhacBi403() throws Exception {
        for (int i = 0; i < ENDPOINTS.size(); i++) {
            Endpoint e = ENDPOINTS.get(i);
            assertThat(call(e, withKey(e.key()))).as(e.key()).isNotIn(401, 403, 500);
            Endpoint other = ENDPOINTS.get((i + 1) % ENDPOINTS.size());
            assertThat(call(e, withKey(other.key()))).as(e.key() + " với khóa " + other.key()).isEqualTo(403);
        }
    }

    @Test
    void adminTenantQuaKiemKhoaKhiCoModuleSales() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            assertThat(call(e, admin())).as(e.key()).isNotIn(401, 403, 500);
        }
    }

    @Test
    void tokenKhongCoModuleSalesBi403ModuleNotEnabledKeCaAdmin() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            for (RequestPostProcessor token : List.of(
                    withKeys(WebTokens.TENANT, List.of("LENDING"), e.key()),
                    withKeys(WebTokens.TENANT, null, e.key()), // thiếu claim modules: coi như chỉ có LENDING
                    admin(List.of("LENDING")))) {
                var req = request(e.method(), e.url()).with(token);
                if (e.body() != null) {
                    req.contentType(MediaType.APPLICATION_JSON).content(e.body());
                }
                mvc.perform(req).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            }
        }
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequired() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            var req = request(e.method(), e.url()).with(withKeys(null, List.of("SALES"), e.key()));
            if (e.body() != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(e.body());
            }
            mvc.perform(req).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    /** Tập khóa thật (PermissionKeyResolver trên mọi handler) phải đúng tập đã công bố cho migration seed (B1.6). */
    @Test
    void tapKhoaThatKhopTapDaCongBo() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Set<String> actual = new TreeSet<>();
        for (HandlerMethod h : handlerMapping.getHandlerMethods().values()) {
            if (h.getBeanType().getName().startsWith("lms.sales.")) {
                PermissionKeyResolver.Resolved r = resolver.resolve(h);
                assertThat(r.mode()).as(h.toString()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
                assertThat(r.source()).as(h.toString()).isEqualTo(PermissionKeyResolver.SOURCE_AUTO);
                assertThat(r.requiredModule()).as(h.toString()).isEqualTo(lms.security.permission.TenantModule.SALES);
                actual.add(r.key());
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(ENDPOINTS.stream().map(Endpoint::key).toList());
        assertThat(actual).hasSize(49);
    }
}
