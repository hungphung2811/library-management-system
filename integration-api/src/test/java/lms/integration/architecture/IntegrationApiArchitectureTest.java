package lms.integration.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/** integration-api là BFF không DB, không nghiệp vụ, không dùng code của service khác (D2). */
@AnalyzeClasses(packages = "lms.integration", importOptions = ImportOption.DoNotIncludeTests.class)
class IntegrationApiArchitectureTest {

    @ArchTest
    static final ArchRule khongDungDbKafkaRedisVaCodeCuaService = noClasses()
            .that().resideInAPackage("lms.integration..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.jdbc..", "org.springframework.data.jpa..",
                    "org.springframework.data.jdbc..", "org.springframework.data.mongodb..",
                    "org.springframework.data.repository..",
                    "org.springframework.kafka..", "org.apache.kafka..", "lms.api..", "lms.customer..",
                    "lms.sales..", "lms.catalog..", "lms.identity..", "lms.system..", "lms.circulation..",
                    "lms.support..", "lms.security..", "lms.tenant..", "lms.cache..");

    /** Redis chỉ dùng cho bộ đếm giới hạn tốc độ (D6) và kiểm thu hồi token: ngoài hai package đó không ai biết Redis. */
    @ArchTest
    static final ArchRule redisChiODoanRateLimit = noClasses()
            .that().resideInAPackage("lms.integration..")
            .and().resideOutsideOfPackages("lms.integration.ratelimit..", "lms.integration.revocation..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..");

    /** Controller chỉ biết interface client và DTO, không biết RestClient hay cài đặt HTTP. */
    @ArchTest
    static final ArchRule controllerKhongDungRestClientHayCaiDatHttp = noClasses()
            .that().resideInAPackage("lms.integration.*.web..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web.client..",
                    "lms.integration.*.client.http..");

    @ArchTest
    static final ArchRule clientKhongBietTangWeb = noClasses()
            .that().resideInAPackage("lms.integration.*.client..")
            .should().dependOnClassesThat().resideInAPackage("lms.integration.*.web..");

    @ArchTest
    static final ArchRule dtoKhongPhuThuocCaiDatHttp = noClasses()
            .that().resideInAPackage("lms.integration.*.dto..")
            .should().dependOnClassesThat().resideInAPackage("lms.integration.*.client.http..");

    /** Controller chỉ trả kiểu trong package dto hoặc kiểu chuẩn (không DTO nội bộ của service khác). */
    @ArchTest
    static final ArchRule controllerChiTraKieuTrongDtoHoacKieuChuan = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .and().arePublic()
            .should(trongSoDtoHoacKieuChuan());

    private static ArchCondition<JavaMethod> trongSoDtoHoacKieuChuan() {
        return new ArchCondition<>("chỉ trả kiểu trong package dto hoặc kiểu chuẩn (ResponseEntity, java.*)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass type : method.getReturnType().getAllInvolvedRawTypes()) {
                    boolean ok = type.getPackageName().startsWith("java.")
                            || type.getName().equals("org.springframework.http.ResponseEntity")
                            || type.getPackageName().matches("lms\\.integration\\..*dto.*")
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
            .that().resideInAPackage("lms.integration..")
            .should().dependOnClassesThat().haveSimpleName("RequirePermission");
}
