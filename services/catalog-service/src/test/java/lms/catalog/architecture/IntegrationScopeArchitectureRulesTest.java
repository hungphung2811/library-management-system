package lms.catalog.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.IntegrationScopeArchitectureRules;

/**
 * Luật dùng chung cho @IntegrationScope (SAAS_API_AUTHENTICATION_DESIGN D3). Chưa method nào dùng annotation nên luật
 * pass rỗng; bắt vi phạm ngay khi pha I3 gắn scope vào controller.
 */
@AnalyzeClasses(packages = "lms.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class IntegrationScopeArchitectureRulesTest {

    @ArchTest
    static final ArchRule chiTrenMethodMappingTrongWeb = IntegrationScopeArchitectureRules.onlyOnMappingMethodsInWeb();

    @ArchTest
    static final ArchRule khongTrenClass = IntegrationScopeArchitectureRules.notOnClasses();

    @ArchTest
    static final ArchRule giaTriThuocTapScopeHopLe = IntegrationScopeArchitectureRules.valueMustBeKnownScope();

    @ArchTest
    static final ArchRule scopeGhiKhongTrenGet = IntegrationScopeArchitectureRules.writeScopeNotOnGet();
}
