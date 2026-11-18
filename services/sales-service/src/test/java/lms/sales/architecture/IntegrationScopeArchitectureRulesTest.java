package lms.sales.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import lms.security.architecture.IntegrationScopeArchitectureRules;
import lms.security.permission.IntegrationScope;
import org.junit.jupiter.api.Test;

/**
 * Luật dùng chung cho @IntegrationScope (SAAS_API_AUTHENTICATION_DESIGN D3, pha I3): chỉ trên method @*Mapping của
 * infrastructure.web, không trên class, scope thuộc tập hợp lệ, scope ghi không trên @GetMapping. Ngoài ra khóa danh
 * sách đóng các method sales được mở cho token integration (thêm method mới phải sửa test này và ghi tài liệu API).
 */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class IntegrationScopeArchitectureRulesTest {

    @ArchTest
    static final ArchRule chiTrenMethodMappingTrongWeb = IntegrationScopeArchitectureRules.onlyOnMappingMethodsInWeb();

    @ArchTest
    static final ArchRule khongTrenClass = IntegrationScopeArchitectureRules.notOnClasses();

    @ArchTest
    static final ArchRule giaTriThuocTapScopeHopLe = IntegrationScopeArchitectureRules.valueMustBeKnownScope();

    @ArchTest
    static final ArchRule scopeGhiKhongTrenGet = IntegrationScopeArchitectureRules.writeScopeNotOnGet();

    @Test
    void danhSachDongCacMethodMoChoTokenIntegration() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("lms.sales");
        List<String> annotated = classes.stream().flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(IntegrationScope.class))
                .map(m -> m.getOwner().getSimpleName() + "/" + m.getName() + "="
                        + m.getAnnotationOfType(IntegrationScope.class).value())
                .sorted().toList();
        assertThat(annotated).containsExactly("SalesOrderController/get=orders:read",
                "SalesOrderController/search=orders:read", "StockItemController/get=inventory:read",
                "StockItemController/search=inventory:read");
    }
}
