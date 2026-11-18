package lms.sales.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.sales", importOptions = ImportOption.DoNotIncludeTests.class)
class SalesArchitectureTest {

    @ArchTest
    static final ArchRule domainKhongPhuThuocFrameworkVaTangNgoai = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "com.fasterxml..",
                    "..application..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationKhongPhuThuocInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    /** ADR-0024: handler nhập Excel ở application chỉ dùng hợp đồng lms.imports.core (không engine/web/xlsx/redis). */
    @ArchTest
    static final ArchRule applicationChiDungImportCore = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.imports.engine..", "lms.imports.web..",
                    "lms.imports.xlsx..", "lms.imports.redis..", "lms.imports.autoconfigure..")
            .allowEmptyShould(true);

    /** Domain không biết thư viện nhập. */
    @ArchTest
    static final ArchRule domainKhongBietImportCommon = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("lms.imports..")
            .allowEmptyShould(true);

    /** Context chỉ dùng tầng application của context khác (ordering gọi stock.application/settings.application). */
    @ArchTest
    static final ArchRule contextChiDungTangApplicationCuaContextKhac = noClasses()
            .that().resideInAPackage("lms.sales.ordering..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.sales.stock.domain..", "lms.sales.stock.infrastructure..",
                    "lms.sales.settings.domain..", "lms.sales.settings.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule stockVaSettingsKhongBietOrdering = noClasses()
            .that().resideInAnyPackage("lms.sales.stock..", "lms.sales.settings..")
            .should().dependOnClassesThat().resideInAPackage("lms.sales.ordering..")
            .allowEmptyShould(true);

    /** ADR-0015: contact chỉ dùng application của settings (và shared); stock/ordering không dùng được. */
    @ArchTest
    static final ArchRule contactChiDungTangApplicationCuaContextKhac = noClasses()
            .that().resideInAPackage("lms.sales.contact..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.sales.ordering..", "lms.sales.stock..",
                    "lms.sales.settings.domain..", "lms.sales.settings.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule cacContextKhacKhongBietContact = noClasses()
            .that().resideInAnyPackage("lms.sales.ordering..", "lms.sales.stock..", "lms.sales.settings..")
            .should().dependOnClassesThat().resideInAPackage("lms.sales.contact..")
            .allowEmptyShould(true);

    /** ADR-0019: banner chỉ phụ thuộc shared (không stock, ordering, settings, contact); không context nào dùng banner. */
    @ArchTest
    static final ArchRule bannerChiPhuThuocShared = noClasses()
            .that().resideInAPackage("lms.sales.banner..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.sales.ordering..", "lms.sales.stock..", "lms.sales.settings..", "lms.sales.contact..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule cacContextKhacKhongBietBanner = noClasses()
            .that().resideInAnyPackage("lms.sales.ordering..", "lms.sales.stock..", "lms.sales.settings..",
                    "lms.sales.contact..")
            .should().dependOnClassesThat().resideInAPackage("lms.sales.banner..")
            .allowEmptyShould(true);

    /** Entity JPA là chi tiết của adapter: không lộ ra ngoài gói persistence (domain/application dùng POJO). */
    @ArchTest
    static final ArchRule entityJpaKhongLoRaNgoaiPersistence = noClasses()
            .that().resideOutsideOfPackage("..infrastructure.persistence..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("JpaEntity");

    @ArchTest
    static final ArchRule webKhongGoiThangPersistence = noClasses()
            .that().resideInAPackage("..infrastructure.web..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.persistence..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacContext =
            slices().matching("lms.sales.(*)..").should().beFreeOfCycles();
}
