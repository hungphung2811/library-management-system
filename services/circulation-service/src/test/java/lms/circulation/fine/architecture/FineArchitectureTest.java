package lms.circulation.fine.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.circulation", importOptions = ImportOption.DoNotIncludeTests.class)
class FineArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("lms.circulation.fine.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("lms.circulation.fine.application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    /** fine là context bị động: không biết borrowing hay reservation, nên không thể có vòng phụ thuộc. */
    @ArchTest
    static final ArchRule fineKhongBietBorrowingVaReservation = noClasses()
            .that().resideInAPackage("lms.circulation.fine..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.circulation.borrowing..", "lms.circulation.reservation..");

    @ArchTest
    static final ArchRule borrowingChiDungTangApplicationCuaFineVaReservation = noClasses()
            .that().resideInAPackage("lms.circulation.borrowing..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.circulation.fine.domain..", "lms.circulation.fine.infrastructure..",
                    "lms.circulation.reservation.domain..", "lms.circulation.reservation.infrastructure..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacContext =
            slices().matching("lms.circulation.(*)..").should().beFreeOfCycles();
}
