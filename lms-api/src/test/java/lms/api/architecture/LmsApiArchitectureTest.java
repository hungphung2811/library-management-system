package lms.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.api", importOptions = ImportOption.DoNotIncludeTests.class)
class LmsApiArchitectureTest {

    /** Controller chỉ biết interface client và DTO, không biết RestClient hay cài đặt HTTP. */
    @ArchTest
    static final ArchRule controllerKhongDungRestClientHayCaiDatHttp = noClasses()
            .that().resideInAPackage("lms.api.*.web..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..", "lms.api.*.client.http..");

    @ArchTest
    static final ArchRule clientKhongBietControllerVaTangWeb = noClasses()
            .that().resideInAPackage("lms.api.*.client..")
            .should().dependOnClassesThat().resideInAPackage("lms.api.*.web..");

    /** Mỗi context chỉ dùng DTO/client của chính nó; context khác không được chạm vào nhau. */
    @ArchTest
    static final ArchRule catalogKhongBietContextKhac = noClasses()
            .that().resideInAPackage("lms.api.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.api.identity..", "lms.api.circulation..", "lms.api.support..");
}
