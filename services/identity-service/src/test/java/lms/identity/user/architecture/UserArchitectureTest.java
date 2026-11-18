package lms.identity.user.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.identity.user", importOptions = ImportOption.DoNotIncludeTests.class)
class UserArchitectureTest {

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

    /** ADR-0024: application chỉ dùng SPI của import-common (lms.imports.core), không dùng engine/web/xlsx/redis. */
    @ArchTest
    static final ArchRule applicationChiDungImportCore = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.imports.engine..", "lms.imports.web..",
                    "lms.imports.xlsx..", "lms.imports.redis..", "lms.imports.autoconfigure..");

    @ArchTest
    static final ArchRule domainKhongDungImportCommon = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("lms.imports..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacPackageCap1 =
            slices().matching("lms.identity.user.(*)..").should().beFreeOfCycles();
}
