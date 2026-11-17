package lms.sales.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.ModuleArchitectureRules;
import lms.security.permission.TenantModule;

/** ADR-0009/0010: mọi controller của sales-service phải có @RequiresModule(SALES). Luật không cho phép rỗng: có 7 controller. */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureRulesTest {

    @ArchTest
    static final ArchRule moiControllerPhaiThuocSales =
            ModuleArchitectureRules.controllersMustRequireModule(TenantModule.SALES);
}
