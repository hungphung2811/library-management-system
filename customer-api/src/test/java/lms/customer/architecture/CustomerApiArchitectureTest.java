package lms.customer.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** customer-api là BFF không DB và không nghiệp vụ tiền/tồn kho (ADR-0011 2.9). */
@AnalyzeClasses(packages = "lms.customer", importOptions = ImportOption.DoNotIncludeTests.class)
class CustomerApiArchitectureTest {

    @ArchTest
    static final ArchRule khongDungDbKafkaVaCodeCuaService = noClasses()
            .that().resideInAPackage("lms.customer..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.jdbc..", "org.springframework.data..",
                    "org.springframework.kafka..", "org.apache.kafka..", "lms.api..", "lms.sales..", "lms.catalog..", "lms.identity..",
                    "lms.system..", "lms.circulation..", "lms.support..", "lms.security..", "lms.tenant..");

    /** Controller chỉ biết interface client và DTO, không biết RestClient hay cài đặt HTTP. */
    @ArchTest
    static final ArchRule controllerKhongDungRestClientHayCaiDatHttp = noClasses()
            .that().resideInAPackage("lms.customer.*.web..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..",
                    "lms.customer.*.client.http..");

    @ArchTest
    static final ArchRule clientKhongBietTangWeb = noClasses()
            .that().resideInAPackage("lms.customer.*.client..")
            .should().dependOnClassesThat().resideInAPackage("lms.customer.*.web..");

    @ArchTest
    static final ArchRule dtoKhongPhuThuocCaiDatHttp = noClasses()
            .that().resideInAPackage("lms.customer.*.dto..")
            .should().dependOnClassesThat().resideInAPackage("lms.customer.*.client.http..");

    /** Nhóm chỉ được dùng web của chính nó (lms.customer.web dùng chung thì được). */
    @ArchTest
    static final ArchRule siteKhongDungWebNhomKhac = noClasses()
            .that().resideInAPackage("lms.customer.site..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.auth.web..", "lms.customer.storefront.web..");

    @ArchTest
    static final ArchRule authKhongDungWebNhomKhac = noClasses()
            .that().resideInAPackage("lms.customer.auth..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.site.web..", "lms.customer.storefront.web..");

    @ArchTest
    static final ArchRule storefrontKhongDungWebNhomKhac = noClasses()
            .that().resideInAPackage("lms.customer.storefront..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.site.web..", "lms.customer.auth.web..");

    /** Nhóm mới (catalog = danh mục sách, shop = đơn hàng) cũng chỉ dùng web của chính nó. */
    @ArchTest
    static final ArchRule catalogKhongDungWebNhomKhac = noClasses()
            .that().resideInAPackage("lms.customer.catalog..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.site.web..", "lms.customer.auth.web..",
                    "lms.customer.storefront.web..", "lms.customer.shop.web..");

    @ArchTest
    static final ArchRule shopKhongDungWebNhomKhac = noClasses()
            .that().resideInAPackage("lms.customer.shop..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.site.web..", "lms.customer.auth.web..",
                    "lms.customer.storefront.web..", "lms.customer.catalog.web..");

    @ArchTest
    static final ArchRule nhomCuKhongDungWebCuaCatalogVaShop = noClasses()
            .that().resideInAnyPackage("lms.customer.site..", "lms.customer.auth..", "lms.customer.storefront..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.customer.catalog.web..", "lms.customer.shop.web..");

    /**
     * Controller công khai chỉ trả record trong package {@code ..dto..} (không entity/DTO nội bộ của service khác): mọi
     * kiểu liên quan tới kiểu trả về (kể cả tham số generic của ResponseEntity/List) phải thuộc {@code lms.customer..dto},
     * hoặc là kiểu chuẩn Java/ResponseEntity.
     */
    @ArchTest
    static final ArchRule controllerCongKhaiChiTraRecordTrongDto = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .and().arePublic()
            .should(trongSoDtoHoacKieuChuan());

    private static ArchCondition<JavaMethod> trongSoDtoHoacKieuChuan() {
        return new ArchCondition<>("chỉ trả kiểu trong package dto hoặc kiểu chuẩn (ResponseEntity, List, java.*)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass type : method.getReturnType().getAllInvolvedRawTypes()) {
                    boolean ok = type.getPackageName().startsWith("java.")
                            || type.getName().equals("org.springframework.http.ResponseEntity")
                            || type.getPackageName().matches("lms\\.customer\\..*dto.*")
                            || type.getName().equals("void");
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName() + " trả " + type.getName()
                                + " (không thuộc package dto)"));
                    }
                }
            }
        };
    }

    @ArchTest
    static final ArchRule khongDungRequirePermission = noClasses()
            .that().resideInAPackage("lms.customer..")
            .should().dependOnClassesThat().haveSimpleName("RequirePermission");
}
