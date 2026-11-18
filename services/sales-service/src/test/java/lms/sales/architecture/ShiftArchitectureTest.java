package lms.sales.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR-0030 gói B: context {@code shift} độc lập với ordering (ordering gọi shift qua port ở application, không ngược
 * lại), nên không có vòng phụ thuộc; shift.domain thuần Java; ordering chỉ dùng tầng application của shift.
 */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class ShiftArchitectureTest {

    @ArchTest
    static final ArchRule shiftKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.sales.shift..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.sales.ordering..", "lms.sales.stock..",
                    "lms.sales.settings..", "lms.sales.contact..", "lms.sales.banner..", "lms.sales.reporting..",
                    "lms.sales.invoicing..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orderingChiDungTangApplicationCuaShift = noClasses()
            .that().resideInAPackage("lms.sales.ordering..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.sales.shift.domain..",
                    "lms.sales.shift.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule shiftDomainChiPhuThuocShared = noClasses()
            .that().resideInAPackage("lms.sales.shift.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..",
                    "org.hibernate..", "lms.sales.shift.application..", "lms.sales.shift.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule useCaseShiftLaPojoKhongDungAnnotationSpringNgoaiTransactional = noClasses()
            .that().resideInAPackage("lms.sales.shift.application..")
            .should().beAnnotatedWith("org.springframework.stereotype.Service")
            .orShould().beAnnotatedWith("org.springframework.stereotype.Component")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entityShiftChiTrongPersistence = classes()
            .that().haveSimpleNameEndingWith("JpaEntity").and().resideInAPackage("lms.sales.shift..")
            .should().resideInAPackage("lms.sales.shift.infrastructure.persistence..");

    @ArchTest
    static final ArchRule controllerShiftKhongDungHasAuthorityHayRequirePermission = noClasses()
            .that().resideInAPackage("lms.sales.shift.infrastructure.web..")
            .should().dependOnClassesThat().haveFullyQualifiedName("lms.security.permission.RequirePermission")
            .allowEmptyShould(true);
}
