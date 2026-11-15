package lms.circulation.tenant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.circulation", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("lms.circulation.tenant.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..", "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("lms.circulation.tenant.application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    /** Context tenant chỉ dùng tầng application/domain của borrowing và reservation. */
    @ArchTest
    static final ArchRule chiDungTangApplicationCuaContextKhac = noClasses()
            .that().resideInAPackage("lms.circulation.tenant..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.circulation.borrowing.infrastructure..", "lms.circulation.reservation.infrastructure..");
}
