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
}
