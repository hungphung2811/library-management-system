package lms.sales.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import lms.security.architecture.TenantDataArchitectureRules;

/**
 * ADR-0031 (F1): {@code @TenantDataAccess} chỉ ở method {@code @*Mapping} của infrastructure.web, và context
 * {@code tenantdata} chỉ đọc dữ liệu tenant của riêng nó (không phụ thuộc context nghiệp vụ khác, không ngược lại).
 */
@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class TenantDataArchitectureTest {

    @ArchTest
    static final ArchRule tenantDataAccessChiODoWeb = TenantDataArchitectureRules.onlyOnMappingMethodsInWeb();

    @ArchTest
    static final ArchRule tenantdataKhongBietContextNghiepVuKhac = noClasses()
            .that().resideInAPackage("lms.sales.tenantdata..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.sales.ordering..", "lms.sales.stock..",
                    "lms.sales.settings..", "lms.sales.contact..", "lms.sales.banner..", "lms.sales.invoicing..",
                    "lms.sales.shift..", "lms.sales.reporting..");

    @ArchTest
    static final ArchRule contextKhacKhongBietTenantdata = noClasses()
            .that().resideOutsideOfPackage("lms.sales.tenantdata..")
            .should().dependOnClassesThat().resideInAPackage("lms.sales.tenantdata..");

    /** Exporter chỉ đọc: không dùng EntityManager/Hibernate ghi, không repository JPA. */
    @ArchTest
    static final ArchRule tenantdataKhongDungJpa = noClasses()
            .that().resideInAPackage("lms.sales.tenantdata..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.hibernate..");
}
