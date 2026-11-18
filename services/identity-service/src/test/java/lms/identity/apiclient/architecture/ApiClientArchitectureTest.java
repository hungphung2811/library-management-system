package lms.identity.apiclient.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Kiến trúc nội bộ của context apiclient. Ranh giới với context khác ở IdentityArchitectureTest. */
@AnalyzeClasses(packages = "lms.identity.apiclient", importOptions = ImportOption.DoNotIncludeTests.class)
class ApiClientArchitectureTest {

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

    /** Tầng application chỉ dùng @Transactional của Spring (không web/jpa/jackson). */
    @ArchTest
    static final ArchRule applicationKhongDungWebHayJpa = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.data..", "jakarta..", "com.fasterxml..");

    @ArchTest
    static final ArchRule khongCoVongLapGiuaCacPackageCap1 =
            slices().matching("lms.identity.apiclient.(*)..").should().beFreeOfCycles();

    /** Không có @RequirePermission: quyền động theo khóa Controller/method (ADR-0003). */
    @ArchTest
    static final ArchRule khongDungRequirePermission = noClasses()
            .that().resideInAPackage("lms.identity.apiclient..")
            .should().dependOnClassesThat().haveFullyQualifiedName("lms.security.permission.RequirePermission");
}
