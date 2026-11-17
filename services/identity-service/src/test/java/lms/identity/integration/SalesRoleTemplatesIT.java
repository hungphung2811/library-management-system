package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * B1.6: mẫu role SALES THẬT (V17, không tự chèn như TenantModulesIT) được chép khi tạo tenant có SALES và khi bật SALES
 * cho tenant có sẵn, dưới vai trò identity_app (chịu RLS). Role Thu ngân không có createWithDiscount/getContact.
 * Cần Docker. Chạy: mvn -pl services/identity-service -am test -Dtest=SalesRoleTemplatesIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "auth.bootstrap-admin.password=Admin@12345",
        "auth.bootstrap.platform-admin-username=platform-root",
        "auth.bootstrap.platform-admin-password=PlatformRoot@123",
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
class SalesRoleTemplatesIT {

    static final String ADMIN_PASSWORD = "TempAdmin@123";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    private String platformToken() {
        ResponseEntity<Map> response = rest.postForEntity("/internal/auth/login",
                Map.of("username", "platform-root", "password", "PlatformRoot@123"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private UUID createTenant(String code, List<String> modules) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Cửa hàng " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        if (modules != null) body.put("modules", modules);
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(), body, Map.class);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private List<String> roleNames(UUID tenantId) {
        return TenantContext.callAs(tenantId, () -> jdbc.queryForList("SELECT name FROM roles ORDER BY name",
                String.class));
    }

    private Set<String> rolePermissions(UUID tenantId, String role) {
        return Set.copyOf(TenantContext.callAs(tenantId, () -> jdbc.queryForList(
                "SELECT rp.permission_code FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                        + "WHERE r.name = ?", String.class, role)));
    }

    private void assertSalesRolesCopied(UUID tenantId) {
        assertThat(roleNames(tenantId)).contains("CASHIER", "STOCK_KEEPER", "STORE_MANAGER");

        Set<String> cashier = rolePermissions(tenantId, "CASHIER");
        assertThat(cashier).containsExactlyInAnyOrder("PosOrderController/create", "SalesOrderController/search",
                "SalesOrderController/get", "SalesOrderController/receipt", "StockItemController/search",
                "StockItemController/get", "TitleController/lookup",
                // V20: trả/đổi trong hạn và thao tác đơn tại quầy
                "SalesReturnController/create", "SalesReturnController/get", "SalesReturnController/listByOrder",
                "SalesOrderController/confirm", "SalesOrderController/markPaid",
                "SalesOrderController/markReadyForPickup", "SalesOrderController/markShipping",
                "SalesOrderController/complete", "SalesOrderController/cancel");
        assertThat(cashier).doesNotContain("PosOrderController/createWithDiscount", "SalesOrderController/getContact",
                "SalesOrderController/applyDiscount", "SalesReturnController/createLate");

        Set<String> manager = rolePermissions(tenantId, "STORE_MANAGER");
        // 41 khóa sau V20 + 5 khóa SalesContactController (V22, ADR-0015)
        assertThat(manager).hasSize(46).contains("PosOrderController/createWithDiscount", "SalesContactController/getContact",
                "SalesOrderController/getContact", "SalesSettingsController/update", "TitleController/lookup",
                "SalesOrderController/applyDiscount", "SalesReturnController/createLate", "RefundController/markRefunded",
                "SalesAuditController/search").noneMatch(k -> k.startsWith("ShopController/"));

        // Khách hàng: chỉ khóa "của tôi" + lookup, role đăng ký mặc định của tenant
        assertThat(roleNames(tenantId)).contains("CUSTOMER");
        assertThat(rolePermissions(tenantId, "CUSTOMER")).containsExactlyInAnyOrder("ShopController/placeOrder",
                "ShopController/myOrders", "ShopController/myOrder", "ShopController/cancelMyOrder",
                "TitleController/lookup", "ShopContactController/create");
        assertThat(TenantContext.callAs(tenantId, () -> jdbc.queryForList(
                "SELECT name FROM roles WHERE signup_default", String.class))).containsExactly("CUSTOMER");

        Set<String> keeper = rolePermissions(tenantId, "STOCK_KEEPER");
        assertThat(keeper).hasSize(16).contains("GoodsReceiptController/confirm", "StockAdjustmentController/stocktake",
                "TitleController/lookup").doesNotContain("PosOrderController/create",
                "PosOrderController/createWithDiscount", "SalesOrderController/getContact");
    }

    @Test
    void taoTenantCoSalesChepBaRoleMauThat() {
        UUID tenantId = createTenant("it-sales-new", List.of("LENDING", "SALES"));
        assertSalesRolesCopied(tenantId);
    }

    @Test
    void tenantChiLendingKhongCoRoleSalesVaBatSalesSauDoChepDungMotLan() {
        String operator = platformToken();
        UUID tenantId = createTenant("it-sales-later", null);
        assertThat(roleNames(tenantId)).doesNotContain("CASHIER", "STOCK_KEEPER", "STORE_MANAGER");

        ResponseEntity<Map> enabled = call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/SALES",
                operator, null, Map.class);
        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertSalesRolesCopied(tenantId);

        // quản trị tenant bớt một khóa của Thu ngân; bật lại không ghi đè, không nhân bản
        TenantContext.runAs(tenantId, () -> jdbc.update("DELETE FROM role_permissions WHERE permission_code = "
                + "'StockItemController/get' AND role_id = (SELECT id FROM roles WHERE name = 'CASHIER')"));
        int roles = roleNames(tenantId).size();
        call(HttpMethod.PUT, "/api/platform/tenants/" + tenantId + "/modules/SALES", operator, null, Map.class);
        assertThat(roleNames(tenantId)).hasSize(roles);
        assertThat(rolePermissions(tenantId, "CASHIER")).doesNotContain("StockItemController/get");
    }
}
