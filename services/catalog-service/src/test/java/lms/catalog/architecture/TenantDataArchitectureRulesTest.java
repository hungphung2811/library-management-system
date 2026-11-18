package lms.catalog.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.TenantDataArchitectureRules;

/** ADR-0031 D1: {@code @TenantDataAccess} chỉ trên method {@code @*Mapping} của {@code infrastructure.web}. */
@AnalyzeClasses(packages = "lms.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantDataArchitectureRulesTest {

    @ArchTest
    static final ArchRule chiTrenMethodMappingTrongWeb = TenantDataArchitectureRules.onlyOnMappingMethodsInWeb();
}
