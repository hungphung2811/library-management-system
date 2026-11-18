package lms.catalog.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.CreateWorkUseCase;
import lms.catalog.catalog.application.GetTitlePreviewUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.GetWorkUseCase;
import lms.catalog.catalog.application.LookupTitlesByIsbnUseCase;
import lms.catalog.catalog.application.LookupTitlesByKeywordUseCase;
import lms.catalog.catalog.application.LookupTitlesUseCase;
import lms.catalog.catalog.application.MergeWorksUseCase;
import lms.catalog.catalog.application.MoveEditionToWorkUseCase;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.SearchWorksUseCase;
import lms.catalog.catalog.application.UpdateTitlePreviewUseCase;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.application.UpdateWorkUseCase;
import lms.catalog.catalog.infrastructure.web.SeriesController;
import lms.catalog.catalog.infrastructure.web.TitleController;
import lms.catalog.catalog.infrastructure.web.WorkController;
import lms.catalog.config.SecurityConfiguration;
import lms.catalog.inventory.application.PublishEditionWorkChangedUseCase;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Đối chiếu V34__work_series_permissions.sql (12 khóa tác phẩm, bộ sách, đổi tác phẩm của ấn bản) với controller THẬT của
 * catalog-service: khóa, HTTP method, đường dẫn BFF (/internal/x thành /api/x) và service. Đổi tên lớp/method hoặc đường dẫn
 * mà không cập nhật migration (hoặc ngược lại) làm test này fail.
 */
@WebMvcTest({WorkController.class, SeriesController.class, TitleController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class WorkSeriesPermissionSeedMatchesCatalogCodeTest {

    private static final Pattern ROW = Pattern.compile(
            "\\('([A-Za-z]+/[A-Za-z]+)',\\s*'catalog-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)");

    @Autowired RequestMappingHandlerMapping handlerMapping;

    @MockitoBean SearchWorksUseCase searchWorks;
    @MockitoBean GetWorkUseCase getWork;
    @MockitoBean CreateWorkUseCase createWork;
    @MockitoBean UpdateWorkUseCase updateWork;
    @MockitoBean MergeWorksUseCase mergeWorks;
    @MockitoBean MoveEditionToWorkUseCase moveEditionToWork;
    @MockitoBean PublishEditionWorkChangedUseCase editionEvents;
    @MockitoBean ReferenceUseCases referenceUseCases;
    @MockitoBean CreateTitleUseCase createTitle;
    @MockitoBean GetTitleUseCase getTitle;
    @MockitoBean SearchTitlesUseCase searchTitles;
    @MockitoBean UpdateTitleUseCase updateTitle;
    @MockitoBean LookupTitlesUseCase lookupTitles;
    @MockitoBean LookupTitlesByKeywordUseCase lookupByKeyword;
    @MockitoBean LookupTitlesByIsbnUseCase lookupByIsbn;
    @MockitoBean GetTitlePreviewUseCase getPreview;
    @MockitoBean UpdateTitlePreviewUseCase updatePreview;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    private Map<String, String> actual() {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            String key = resolver.resolve(h).key();
            if (key == null) continue; // @AuthenticatedOnly: không có khóa
            boolean mine = key.startsWith("WorkController/") || key.startsWith("SeriesController/")
                    || key.equals("TitleController/changeWork");
            if (!mine) continue;
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next();
            path = path.replace("/internal/", "/api/");
            out.put(key, method + " " + path.replaceAll("\\{[^}/]+}", "{}"));
        }
        return out;
    }

    @Test
    void seedKeysMatchTheRealCatalogControllers() throws Exception {
        // Đọc thẳng file nguồn của system-service (jar system-service trong classpath có thể cũ hơn migration mới)
        java.nio.file.Path file = java.nio.file.Path.of("..", "system-service", "src", "main", "resources", "db",
                "migration", "identity", "V34__work_series_permissions.sql");
        assertThat(file).exists();
        String sql = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> seeded = new TreeMap<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) seeded.put(m.group(1), m.group(2) + " " + m.group(3).replaceAll("\\{[^}/]+}", "{}"));
        Map<String, String> actual = actual();
        assertThat(actual).hasSize(12);
        assertThat(seeded).isEqualTo(actual);
    }
}
