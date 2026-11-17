package lms.identity.contract.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Kiến trúc nội bộ của context hợp đồng (ADR-0012). Ranh giới với context khác ở IdentityArchitectureTest. */
@AnalyzeClasses(packages = "lms.identity.contract", importOptions = ImportOption.DoNotIncludeTests.class)
class ContractArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacPackageCap1 =
            slices().matching("lms.identity.contract.(*)..").should().beFreeOfCycles();
}
