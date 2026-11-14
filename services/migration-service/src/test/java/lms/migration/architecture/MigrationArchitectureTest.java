package lms.migration.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.migration", importOptions = ImportOption.DoNotIncludeTests.class)
class MigrationArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.flywaydb..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocFrameworkVaInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.flywaydb..", "..infrastructure..");

    @ArchTest
    static final ArchRule flywayChiNamTrongInfrastructure = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.flyway..")
            .should().dependOnClassesThat().resideInAPackage("org.flywaydb..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacTang =
            slices().matching("lms.migration.(*)..").should().beFreeOfCycles();
}
