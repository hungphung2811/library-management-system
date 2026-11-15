package lms.identity.tenant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.identity.tenant", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true); // tầng application của tenant chưa có use case ở bước này

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacPackageCap1 =
            slices().matching("lms.identity.tenant.(*)..").should().beFreeOfCycles();
}
