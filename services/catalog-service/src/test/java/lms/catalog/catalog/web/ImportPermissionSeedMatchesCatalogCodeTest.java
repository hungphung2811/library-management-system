package lms.catalog.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.catalog.catalog.infrastructure.web.AuthorImportController;
import lms.catalog.catalog.infrastructure.web.CategoryImportController;
import lms.catalog.catalog.infrastructure.web.PublisherImportController;
import lms.catalog.catalog.infrastructure.web.ShelfLocationImportController;
import lms.catalog.catalog.infrastructure.web.TitleController;
import lms.catalog.catalog.infrastructure.web.TitleImportController;
import lms.catalog.config.SecurityConfiguration;
import lms.imports.engine.ImportWorkflow;
import lms.imports.web.ImportActorResolver;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
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

/**
 * Đối chiếu V31__import_permissions.sql (10 khóa import catalog + TitleController/lookupByIsbn) với controller THẬT của
 * catalog-service: khóa, HTTP method, đường dẫn BFF (/internal/imports/x thành /api/catalog/imports/x), service_name.
 * Đổi tên lớp/method hoặc đổi đường dẫn mà không cập nhật migration (hoặc ngược lại) làm test này fail.
 */
@WebMvcTest({TitleImportController.class, AuthorImportController.class, PublisherImportController.class,
        CategoryImportController.class, ShelfLocationImportController.class, TitleController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ImportPermissionSeedMatchesCatalogCodeTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'catalog-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");

    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockitoBean ImportWorkflow workflow;
    @MockitoBean ImportActorResolver actors;
    @MockitoBean TitleImportHandler titleHandler;
    @MockitoBean(name = "authorImportHandler") ReferenceImportHandler authorHandler;
    @MockitoBean(name = "publisherImportHandler") ReferenceImportHandler publisherHandler;
    @MockitoBean(name = "categoryImportHandler") ReferenceImportHandler categoryHandler;
    @MockitoBean(name = "shelfLocationImportHandler") ReferenceImportHandler shelfHandler;
    @MockitoBean lms.catalog.catalog.application.CreateTitleUseCase createTitle;
    @MockitoBean lms.catalog.catalog.application.GetTitleUseCase getTitle;
    @MockitoBean lms.catalog.catalog.application.SearchTitlesUseCase searchTitles;
    @MockitoBean lms.catalog.catalog.application.UpdateTitleUseCase updateTitle;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesUseCase lookupTitles;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByKeywordUseCase lookupByKeyword;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByIsbnUseCase lookupByIsbn;
    @MockitoBean lms.catalog.catalog.application.GetTitlePreviewUseCase getPreview;
    @MockitoBean lms.catalog.catalog.application.UpdateTitlePreviewUseCase updatePreview;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    private Map<String, String> actual() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            String cls = h.getBeanType().getSimpleName();
            String key = resolver.resolve(h).key();
            if (key == null) continue; // @AuthenticatedOnly: không có khóa
            boolean importKey = cls.endsWith("ImportController") && (key.endsWith("/upload") || key.endsWith("/confirm"));
            if (!importKey && !key.equals("TitleController/lookupByIsbn")) continue;
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next();
            path = path.replace("/internal/imports/", "/api/catalog/imports/").replace("/internal/books/", "/api/books/");
            out.put(key, method + " " + path.replaceAll("\\{[^}/]+}", "{}"));
        }
        return out;
    }

    @Test
    void seedKeysMatchTheRealCatalogControllers() throws Exception {
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
        Map<String, String> actual = actual();
        assertThat(actual).hasSize(11);
        assertThat(seeded).isEqualTo(actual);
    }
}
