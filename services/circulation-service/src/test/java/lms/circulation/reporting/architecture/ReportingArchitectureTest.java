package lms.circulation.reporting.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Context reporting: domain thuần, application không biết infrastructure, không đụng persistence của context khác. */
@AnalyzeClasses(packages = "lms.circulation", importOptions = ImportOption.DoNotIncludeTests.class)
class ReportingArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("lms.circulation.reporting.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("lms.circulation.reporting.application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule reportingKhongDungDomainHayInfrastructureCuaContextKhac = noClasses()
            .that().resideInAPackage("lms.circulation.reporting..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.circulation.borrowing..", "lms.circulation.fine..", "lms.circulation.reservation..",
                    "lms.circulation.policy..", "lms.circulation.tenant..");

    /** Không context nào phụ thuộc ngược vào reporting (read model chỉ là điểm cuối). */
    @ArchTest
    static final ArchRule khongContextNaoPhuThuocReporting = noClasses()
            .that().resideOutsideOfPackage("lms.circulation.reporting..")
            .should().dependOnClassesThat().resideInAPackage("lms.circulation.reporting..");
}
