package lms.sales.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.sales.banner.application.BannerAdminUseCases;
import lms.sales.banner.application.PublicBannerQuery;
import lms.sales.banner.infrastructure.web.SalesBannerController;
import lms.sales.banner.infrastructure.web.StorefrontBannerController;
import lms.sales.config.SecurityConfiguration;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.infrastructure.web.SalesContactController;
import lms.sales.contact.infrastructure.web.ShopContactController;
import lms.sales.ordering.infrastructure.web.PosOrderController;
import lms.sales.ordering.infrastructure.web.RefundController;
import lms.sales.ordering.infrastructure.web.SalesAuditController;
import lms.sales.ordering.infrastructure.web.SalesOrderController;
import lms.sales.ordering.infrastructure.web.SalesReturnController;
import lms.sales.ordering.infrastructure.web.ShopPaymentController;
import lms.sales.ordering.application.ShopPaymentUseCases;
import lms.sales.reporting.application.StorefrontTopSoldUseCase;
import lms.sales.reporting.infrastructure.web.StorefrontTopSoldController;
import lms.sales.ordering.infrastructure.web.ShopController;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.stock.infrastructure.web.GoodsReceiptController;
import lms.sales.stock.infrastructure.web.StockAdjustmentController;
import lms.sales.stock.infrastructure.web.StockItemController;
import lms.sales.stock.infrastructure.web.StorefrontController;
import lms.sales.stock.infrastructure.web.SupplierController;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * B1.6: đối chiếu migration identity seed khóa sales (file {@code V<n>__sales_permissions_and_role_templates.sql} của
 * system-service, có sẵn trên classpath test) với khóa THẬT của controller sales-service. Đặt ở sales-service vì chỉ nơi
 * này có sẵn cả controller thật lẫn jar system-service (test scope); identity không phụ thuộc sales-service. Đổi tên
 * controller/method hoặc thêm endpoint mà không cập nhật migration (hoặc ngược lại) làm test này fail. Kiểm cả
 * service, HTTP method, đường dẫn (service {@code /internal/sales/**} ↔ BFF {@code /api/sales/**}) và mọi khóa mà
 * mẫu role tham chiếu phải có trong tập seed.
 */
@WebMvcTest({SalesSettingsController.class, StockItemController.class, StockAdjustmentController.class,
        SupplierController.class, GoodsReceiptController.class, PosOrderController.class, SalesOrderController.class,
        ShopController.class, StorefrontController.class, SalesReturnController.class, RefundController.class,
        SalesAuditController.class, ShopContactController.class, SalesContactController.class, SalesBannerController.class,
        StorefrontBannerController.class, StorefrontTopSoldController.class, ShopPaymentController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesPermissionSeedMatchesCodeTest {

    private static final Pattern PERMISSION_ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'sales-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");
    private static final Pattern TEMPLATE_KEY = Pattern.compile(
            "\\('00000000-0000-0000-0000-0000000000c[1234]',\\s*'([A-Za-z]+/[A-Za-z]+)'\\)");

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    SubmitShopContactUseCase submitShopContact;

    @MockitoBean
    ShopContactQueryUseCases shopContactQueries;

    @MockitoBean
    ShopContactWorkflowUseCases shopContactWorkflow;

    @MockitoBean
    BannerAdminUseCases bannerAdmin;

    @MockitoBean
    PublicBannerQuery publicBannerQuery;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean
    StorefrontTopSoldUseCase topSoldUseCase;

    @MockitoBean
    ShopPaymentUseCases shopPaymentUseCases;

    private static String migrationSql() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources(
                "classpath*:db/migration/identity/V*__sales_*.sql");
        // V17 (pha 1: 23 khóa), V20 (pha 2: 21 khóa, mẫu Khách hàng) và V22 (liên hệ: 6 khóa) và V26 (banner + gợi ý: 7 khóa) và V28 (chi tiết mặt hàng + ghi chú đơn: 4 khóa) và V39 (top bán chạy + sách theo id: 2 khóa); file seed mới sau này tên sales_* cũng được gộp
        assertThat(found).as("migration seed khóa sales trên classpath (jar system-service)").hasSize(6);
        StringBuilder sql = new StringBuilder();
        for (Resource r : found) {
            try (var in = r.getInputStream()) {
                sql.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sql.toString();
    }

    /** khóa → "METHOD path" theo hợp đồng BFF /api, lấy từ code thật. */
    /** Handler @AuthenticatedOnly của sales gom được ở lần gọi actual() gần nhất. */
    private final Set<String> authenticated = new TreeSet<>();

    private Map<String, String> actual() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!h.getBeanType().getName().startsWith("lms.sales.")) continue;
            PermissionKeyResolver.Resolved resolved = resolver.resolve(h);
            if (resolved.mode() == PermissionKeyResolver.Mode.AUTHENTICATED) {
                // @AuthenticatedOnly: không có khóa, không nằm trong seed; kiểm riêng ở danh sách đã duyệt
                authenticated.add(h.getBeanType().getSimpleName() + "#" + h.getMethod().getName());
                continue;
            }
            String key = resolved.key();
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next();
            // khách (shop) và danh mục công khai (storefront) không đi qua lms-api: seed ghi đường dẫn service nguyên trạng
            if (!path.startsWith("/internal/sales/shop/") && !path.startsWith("/internal/sales/storefront/")) {
                path = path.replace("/internal/", "/api/");
            }
            out.put(key, method + " " + path);
        }
        return out;
    }

    @Test
    void khoaSeedTrungKhoaThatCuaSalesServiceKemMethodVaDuongDan() throws IOException {
        Map<String, String> seeded = new TreeMap<>();
        Matcher m = PERMISSION_ROW.matcher(migrationSql());
        while (m.find()) {
            seeded.put(m.group(1), m.group(2) + " " + m.group(3));
        }
        Map<String, String> actual = actual();
        assertThat(actual).hasSize(63);
        // handler không khóa phải được duyệt có chủ đích (chỉ trả dữ liệu của chính người gọi, ADR-0029 mục 2.5):
        // thêm endpoint @AuthenticatedOnly mới phải khai báo ở đây
        assertThat(authenticated).containsExactlyInAnyOrder("ShopController#myOrderCounts",
                "ShopPaymentController#myOrderPayment", "ShopPaymentController#paymentOptions");
        // path-variable có thể đặt tên khác nhau giữa service và seed: so tên biến sau khi chuẩn hóa về {}
        assertThat(normalize(seeded)).isEqualTo(normalize(actual));
    }

    @Test
    void moiKhoaTrongMauRoleThuNganVaThuKhoCoTrongTapSeed() throws IOException {
        String sql = migrationSql();
        Set<String> known = new TreeSet<>();
        Matcher p = PERMISSION_ROW.matcher(sql);
        while (p.find()) known.add(p.group(1));
        known.add("TitleController/lookup");
        Set<String> referenced = new TreeSet<>();
        Matcher t = TEMPLATE_KEY.matcher(sql);
        while (t.find()) referenced.add(t.group(1));
        assertThat(referenced).isNotEmpty();
        assertThat(known).containsAll(referenced);
        assertThat(sql).contains("TitleController/lookup");
    }

    private static Map<String, String> normalize(Map<String, String> in) {
        Map<String, String> out = new TreeMap<>();
        in.forEach((k, v) -> out.put(k, v.replaceAll("\\{[^}/]+}", "{}")));
        return out;
    }
}
