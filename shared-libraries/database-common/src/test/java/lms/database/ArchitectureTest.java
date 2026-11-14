package lms.database;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "lms.database", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule apiVaCoreKhongPhuThuocProvider = noClasses()
            .that().resideInAnyPackage("lms.database.api..", "lms.database.core..")
            .should().dependOnClassesThat().resideInAPackage("lms.database.provider..");

    @ArchTest
    static final ArchRule apiKhongPhuThuocCacTangKhac = noClasses()
            .that().resideInAPackage("lms.database.api..")
            .should().dependOnClassesThat().resideInAnyPackage("lms.database.config..",
                    "lms.database.core..", "lms.database.provider..",
                    "lms.database.health..", "lms.database.autoconfigure..");

    @ArchTest
    static final ArchRule cacProviderKhongPhuThuocLanNhau =
            slices().matching("lms.database.provider.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule khongCoChuTrinhGiuaCacPackageCap1 =
            slices().matching("lms.database.(*)..").should().beFreeOfCycles();
}
