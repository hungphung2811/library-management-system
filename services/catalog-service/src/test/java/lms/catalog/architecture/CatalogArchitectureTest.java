package lms.catalog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class CatalogArchitectureTest {

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
    static final ArchRule inventoryKhongBietCatalog = noClasses()
            .that().resideInAPackage("lms.catalog.inventory..")
            .should().dependOnClassesThat().resideInAPackage("lms.catalog.catalog..");

    @ArchTest
    static final ArchRule catalogChiDungTangApplicationCuaInventory = noClasses()
            .that().resideInAPackage("lms.catalog.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.catalog.inventory.domain..", "lms.catalog.inventory.infrastructure..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacContext =
            slices().matching("lms.catalog.(*)..").should().beFreeOfCycles();
}
