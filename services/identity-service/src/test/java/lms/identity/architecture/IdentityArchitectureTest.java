package lms.identity.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Ranh giới giữa các bounded context trong identity-service. */
@AnalyzeClasses(packages = "lms.identity", importOptions = ImportOption.DoNotIncludeTests.class)
class IdentityArchitectureTest {

    @ArchTest
    static final ArchRule accessControlKhongBietAuth = noClasses()
            .that().resideInAPackage("lms.identity.accesscontrol..")
            .should().dependOnClassesThat().resideInAPackage("lms.identity.auth..");

    /** auth chỉ được dùng API (tầng application) của accesscontrol, không đụng domain hay infrastructure của nó. */
    @ArchTest
    static final ArchRule authChiDungTangApplicationCuaAccessControl = noClasses()
            .that().resideInAPackage("lms.identity.auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.accesscontrol.domain..",
                    "lms.identity.accesscontrol.infrastructure..");

    /** auth chỉ được dùng API (tầng application) của user. */
    @ArchTest
    static final ArchRule authChiDungTangApplicationCuaUser = noClasses()
            .that().resideInAPackage("lms.identity.auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.user.domain..", "lms.identity.user.infrastructure..");

    /**
     * auth chỉ được dùng API (tầng application) của tenant. Ngoại lệ có chủ đích: NoTenantAccessException (domain của
     * tenant) chỉ đi xuyên qua auth chứ không bị auth tham chiếu, nên luật này vẫn áp nguyên cho mọi lớp của auth.
     */
    @ArchTest
    static final ArchRule authChiDungTangApplicationCuaTenant = noClasses()
            .that().resideInAPackage("lms.identity.auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.tenant.domain..", "lms.identity.tenant.infrastructure..");

    /** tenant là context nền: không biết auth, accesscontrol, user, department hay contract. */
    @ArchTest
    static final ArchRule tenantKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.identity.tenant..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.auth..", "lms.identity.accesscontrol..", "lms.identity.user..",
                    "lms.identity.department..", "lms.identity.contract..", "lms.identity.apiclient..");

    /** contract (ADR-0012) chỉ được dùng API (tầng application) của tenant, không đụng domain hay infrastructure. */
    @ArchTest
    static final ArchRule contractChiDungTangApplicationCuaTenant = noClasses()
            .that().resideInAPackage("lms.identity.contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.tenant.domain..", "lms.identity.tenant.infrastructure..");

    /** contract không biết auth, accesscontrol, user, department hay apiclient. */
    @ArchTest
    static final ArchRule contractKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.identity.contract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.auth..", "lms.identity.accesscontrol..", "lms.identity.user..",
                    "lms.identity.department..", "lms.identity.apiclient..");

    /**
     * apiclient (SaaS API Authentication, Application/credential/scope) chỉ được dùng API (tầng application) của tenant
     * và contract (kiểm hợp đồng ACTIVE cho Application INTEGRATION), không đụng domain hay infrastructure của chúng.
     */
    @ArchTest
    static final ArchRule apiclientChiDungTangApplicationCuaTenantVaContract = noClasses()
            .that().resideInAPackage("lms.identity.apiclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.tenant.domain..", "lms.identity.tenant.infrastructure..",
                    "lms.identity.contract.domain..", "lms.identity.contract.infrastructure..");

    /** apiclient không biết auth, accesscontrol, user hay department. */
    @ArchTest
    static final ArchRule apiclientKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.identity.apiclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.auth..", "lms.identity.accesscontrol..", "lms.identity.user..",
                    "lms.identity.department..");

    /**
     * auth (token endpoint OAuth2 client_credentials, pha I2) chỉ được dùng API (tầng application) của apiclient, không
     * đụng domain hay infrastructure của nó; chỉ adapter infrastructure.client của auth được gọi tầng đó (qua cổng).
     */
    @ArchTest
    static final ArchRule authChiDungTangApplicationCuaApiclient = noClasses()
            .that().resideInAPackage("lms.identity.auth..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.apiclient.domain..", "lms.identity.apiclient.infrastructure..");

    /** Tầng application/domain của auth không biết apiclient: chỉ qua cổng (port) do infrastructure nối. */
    @ArchTest
    static final ArchRule authApplicationVaDomainKhongBietApiclient = noClasses()
            .that().resideInAnyPackage("lms.identity.auth.application..", "lms.identity.auth.domain..")
            .should().dependOnClassesThat().resideInAPackage("lms.identity.apiclient..");

    /** Context nền/độc lập không phụ thuộc apiclient (auth chỉ qua cổng + adapter, xem hai luật ở trên). */
    @ArchTest
    static final ArchRule khongContextNenNaoPhuThuocApiclient = noClasses()
            .that().resideInAnyPackage("lms.identity.accesscontrol..", "lms.identity.user..",
                    "lms.identity.department..")
            .should().dependOnClassesThat().resideInAPackage("lms.identity.apiclient..");

    /** Không context nào khác phụ thuộc vào contract (job/recorder nối vào qua cổng của tenant). */
    @ArchTest
    static final ArchRule khongContextNaoPhuThuocContract = noClasses()
            .that().resideInAnyPackage("lms.identity.auth..", "lms.identity.accesscontrol..", "lms.identity.user..",
                    "lms.identity.department..")
            .should().dependOnClassesThat().resideInAPackage("lms.identity.contract..");

    /** department là context độc lập: không biết auth, accesscontrol hay user. */
    @ArchTest
    static final ArchRule departmentKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.identity.department..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.auth..", "lms.identity.accesscontrol..", "lms.identity.user..");

    /** user chỉ được dùng API (tầng application) của department. */
    @ArchTest
    static final ArchRule userChiDungTangApplicationCuaDepartment = noClasses()
            .that().resideInAPackage("lms.identity.user..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.department.domain..", "lms.identity.department.infrastructure..");

    /** user chỉ được dùng API (tầng application) của tenant (ghi membership, phòng ban trong tenant). */
    @ArchTest
    static final ArchRule userChiDungTangApplicationCuaTenant = noClasses()
            .that().resideInAPackage("lms.identity.user..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "lms.identity.tenant.domain..", "lms.identity.tenant.infrastructure..");

    /** user là context độc lập: không biết auth hay accesscontrol. */
    @ArchTest
    static final ArchRule userKhongBietCacContextKhac = noClasses()
            .that().resideInAPackage("lms.identity.user..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.identity.auth..", "lms.identity.accesscontrol..");
}
