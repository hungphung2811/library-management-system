package lms.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Nhóm dashboard và reports (ADR-0018) theo cùng khuôn với các context khác: tự đủ client/dto/web của mình, không
 * chạm context khác (chỉ dùng chung lms.api.config, lms.api.support.TokenRelayInterceptor, lms.api.web).
 */
@AnalyzeClasses(packages = "lms.api", importOptions = ImportOption.DoNotIncludeTests.class)
class LmsApiDashboardArchitectureTest {

    private static final String[] OTHER_CONTEXTS = {"lms.api.identity..", "lms.api.catalog..", "lms.api.circulation..",
            "lms.api.sales..", "lms.api.system..", "lms.api.support.client..", "lms.api.support.dto..",
            "lms.api.support.web.."};

    @ArchTest
    static final ArchRule dashboardKhongBietContextKhac = noClasses()
            .that().resideInAPackage("lms.api.dashboard..")
            .should().dependOnClassesThat().resideInAnyPackage(concat(OTHER_CONTEXTS, "lms.api.reports.."));

    @ArchTest
    static final ArchRule reportsKhongBietContextKhac = noClasses()
            .that().resideInAPackage("lms.api.reports..")
            .should().dependOnClassesThat().resideInAnyPackage(concat(OTHER_CONTEXTS, "lms.api.dashboard.."));

    @ArchTest
    static final ArchRule contextKhacKhongBietDashboardHayReports = noClasses()
            .that().resideInAnyPackage("lms.api.identity..", "lms.api.catalog..", "lms.api.circulation..",
                    "lms.api.sales..", "lms.api.system..", "lms.api.support.client..", "lms.api.support.dto..",
                    "lms.api.support.web..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.api.dashboard..", "lms.api.reports..");

    /** Controller chỉ biết interface client/service và DTO, không biết RestClient hay cài đặt HTTP (cùng luật chung). */
    @ArchTest
    static final ArchRule controllerMoiKhongDungRestClientHayCaiDatHttp = noClasses()
            .that().resideInAnyPackage("lms.api.dashboard.web..", "lms.api.reports.web..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..",
                    "lms.api.dashboard.client.http..", "lms.api.reports.client.http..");

    @ArchTest
    static final ArchRule clientMoiKhongBietControllerVaTangWeb = noClasses()
            .that().resideInAnyPackage("lms.api.dashboard.client..", "lms.api.reports.client..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.api.dashboard.web..", "lms.api.reports.web..",
                    "lms.api.dashboard.service..");

    private static String[] concat(String[] base, String extra) {
        String[] all = java.util.Arrays.copyOf(base, base.length + 1);
        all[base.length] = extra;
        return all;
    }
}
