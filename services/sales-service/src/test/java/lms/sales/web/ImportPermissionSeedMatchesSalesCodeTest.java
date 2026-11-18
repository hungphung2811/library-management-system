package lms.sales.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.ImportActorResolver;
import lms.sales.config.SecurityConfiguration;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.stock.application.imports.SupplierImportHandler;
import lms.sales.stock.infrastructure.web.StockItemImportController;
import lms.sales.stock.infrastructure.web.SupplierImportController;
import lms.sales.support.HarnessBeans;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Đối chiếu 4 khóa import sales trong V31 với StockItemImportController/SupplierImportController thật. */
@WebMvcTest({StockItemImportController.class, SupplierImportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ImportPermissionSeedMatchesSalesCodeTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'sales-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");

    @Autowired RequestMappingHandlerMapping handlerMapping;
    @MockitoBean ImportWorkflow workflow;
    @MockitoBean ImportActorResolver actors;
    @MockitoBean StockItemImportHandler stockHandler;
    @MockitoBean SupplierImportHandler supplierHandler;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    @Test
    void seedKeysMatchTheRealSalesImportControllers() throws Exception {
        var found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/identity/V31__import_permissions.sql");
        assertThat(found).hasSize(1);
        String sql;
        try (var in = found[0].getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> seeded = new TreeMap<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) seeded.put(m.group(1), m.group(2) + " " + m.group(3).replaceAll("\\{[^}/]+}", "{}"));

        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Map<String, String> actual = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (!h.getBeanType().getSimpleName().endsWith("ImportController")) continue;
            String key = resolver.resolve(h).key();
            if (key == null) continue; // @AuthenticatedOnly: không có khóa
            if (!key.endsWith("/upload") && !key.endsWith("/confirm")) continue;
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next()
                    .replace("/internal/imports/", "/api/sales/imports/");
            actual.put(key, method + " " + path.replaceAll("\\{[^}/]+}", "{}"));
        }
        assertThat(actual).hasSize(4);
        assertThat(seeded).isEqualTo(actual);
    }
}
