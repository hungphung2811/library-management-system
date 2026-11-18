package lms.sales.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR-0030 gói A: context invoicing. Domain Java thuần (luật chung của SalesArchitectureTest cũng áp cho
 * {@code ..domain..}); invoicing không biết context khác (chỉ shared); ordering chỉ dùng TẦNG APPLICATION của invoicing
 * (cổng móc InvoiceAdjustmentPort/InvoiceAutoIssuePort và cổng đọc đơn do ordering cài); các context còn lại không biết
 * invoicing. Không vòng phụ thuộc ordering và invoicing (luật slices của SalesArchitectureTest).
 */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class InvoicingArchitectureTest {

    @ArchTest
    static final ArchRule invoicingChiPhuThuocShared = noClasses()
            .that().resideInAPackage("lms.sales.invoicing..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.sales.ordering..", "lms.sales.stock..", "lms.sales.settings..", "lms.sales.contact..",
                    "lms.sales.banner..", "lms.sales.reporting..", "lms.sales.shift..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule orderingChiDungApplicationCuaInvoicing = noClasses()
            .that().resideInAPackage("lms.sales.ordering..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.sales.invoicing.domain..",
                    "lms.sales.invoicing.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule cacContextKhacKhongBietInvoicing = noClasses()
            .that().resideInAnyPackage("lms.sales.stock..", "lms.sales.settings..", "lms.sales.contact..",
                    "lms.sales.banner..", "lms.sales.reporting..", "lms.sales.shared..")
            .should().dependOnClassesThat().resideInAPackage("lms.sales.invoicing..")
            .allowEmptyShould(true);

    /** Tầng web của invoicing chỉ nói chuyện với application (không persistence, không JPA). */
    @ArchTest
    static final ArchRule webInvoicingChiDungApplication = noClasses()
            .that().resideInAPackage("lms.sales.invoicing.infrastructure.web..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.sales.invoicing.infrastructure.persistence..",
                    "org.springframework.jdbc..", "jakarta.persistence..")
            .allowEmptyShould(true);

    /** Domain hóa đơn không dùng java.sql/Spring/Jackson/jakarta (domain thuần). */
    @ArchTest
    static final ArchRule domainInvoicingThuan = noClasses()
            .that().resideInAPackage("lms.sales.invoicing.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                    "com.fasterxml..", "java.sql..", "org.slf4j..", "org.hibernate..")
            .allowEmptyShould(true);
}
