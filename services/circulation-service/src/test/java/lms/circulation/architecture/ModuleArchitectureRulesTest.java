package lms.circulation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.ModuleArchitectureRules;
import lms.security.permission.TenantModule;

/** ADR-0009: mọi controller của circulation thuộc LENDING, thêm controller mới mà quên @RequiresModule thì test đỏ. */
@AnalyzeClasses(packages = "lms.circulation", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureRulesTest {

    @ArchTest
    static final ArchRule moiControllerPhaiThuocLending =
            ModuleArchitectureRules.controllersMustRequireModule(TenantModule.LENDING);
}
