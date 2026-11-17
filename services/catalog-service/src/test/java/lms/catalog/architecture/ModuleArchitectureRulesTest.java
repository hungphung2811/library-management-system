package lms.catalog.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.ModuleArchitectureRules;
import lms.security.permission.TenantModule;

/** ADR-0009: BookCopyController (bản sao cho mượn) thuộc LENDING; TitleController là lõi nên không bị ràng buộc. */
@AnalyzeClasses(packages = "lms.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureRulesTest {

    @ArchTest
    static final ArchRule bookCopyControllerPhaiThuocLending =
            ModuleArchitectureRules.namedControllersMustRequireModule(TenantModule.LENDING, "BookCopyController");
}
