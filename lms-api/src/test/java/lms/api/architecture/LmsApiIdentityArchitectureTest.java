package lms.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.api", importOptions = ImportOption.DoNotIncludeTests.class)
class LmsApiIdentityArchitectureTest {

    /** Context identity chỉ dùng DTO/client của chính nó. */
    @ArchTest
    static final ArchRule identityKhongBietContextKhac = noClasses()
            .that().resideInAPackage("lms.api.identity..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.api.catalog..", "lms.api.circulation..",
                    "lms.api.support.client..", "lms.api.support.dto..", "lms.api.support.web..");

    @ArchTest
    static final ArchRule contextKhacKhongBietIdentity = noClasses()
            .that().resideInAnyPackage("lms.api.catalog..", "lms.api.circulation..",
                    "lms.api.support.client..", "lms.api.support.dto..", "lms.api.support.web..")
            .should().dependOnClassesThat().resideInAPackage("lms.api.identity..");
}
