package lms.demo.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class DemoSeedGuardTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000d1";

    @Test
    void profileProdBiTuChoiDuDemoSeedBat() {
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("prod", "demo-seed"), true, TENANT, false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class).hasMessageContaining("production");
    }

    @Test
    void profileProductionVaChuHoaCungBiTuChoi() {
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("Production"), true, TENANT, false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class);
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of(" PROD "), true, TENANT, false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class);
    }

    @Test
    void thieuCoEnabledBiTuChoi() {
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("dev", "demo-seed"), false, TENANT, false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class).hasMessageContaining("enabled");
    }

    @Test
    void thieuMatKhauBiTuChoi() {
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("dev"), true, TENANT, false, " "))
                .isInstanceOf(DemoSeedRefusedException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("dev"), true, TENANT, false, null))
                .isInstanceOf(DemoSeedRefusedException.class);
    }

    @Test
    void thieuTenantIdBiTuChoiOServiceKhongTuTaoTenant() {
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("dev"), true, null, false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class).hasMessageContaining("tenant-id");
        assertThatThrownBy(() -> DemoSeedGuard.check(List.of("dev"), true, "khong-phai-uuid", false, "pw"))
                .isInstanceOf(DemoSeedRefusedException.class);
    }

    @Test
    void identityTuTaoTenantKhongCanTenantId() {
        assertThat(DemoSeedGuard.check(List.of("dev", "demo-seed"), true, null, true, "pw")).isNull();
    }

    @Test
    void hopLeTraVeTenant() {
        assertThat(DemoSeedGuard.check(List.of("dev", "demo-seed"), true, TENANT, false, "pw"))
                .isEqualTo(UUID.fromString(TENANT));
    }

    private static DemoSeedRunner runner(DemoSeedProperties props, List<String> profiles, List<DemoSeedStep> steps) {
        return new DemoSeedRunner(props, profiles, steps, Clock.system(DemoSeedRunner.BUSINESS_ZONE),
                new GenericApplicationContext(), code -> { });
    }

    private static DemoSeedStep step(String name, int order, List<String> log, UUID[] tenantSeen) {
        return new DemoSeedStep() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public SeedResult run(DemoSeedContext context) {
                log.add(name);
                tenantSeen[0] = TenantContext.current().orElse(null);
                return SeedResult.of(2, 1);
            }
        };
    }

    @Test
    void runnerKhongChayBuocNaoKhiProdBat() {
        List<String> log = new ArrayList<>();
        DemoSeedProperties props = new DemoSeedProperties(true, TENANT, "pw", false, 1L);
        int code = runner(props, List.of("prod", "demo-seed"), List.of(step("a", 1, log, new UUID[1]))).execute();
        assertThat(code).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(log).isEmpty();
    }

    @Test
    void runnerKhongChayBuocNaoKhiThieuCoEnabled() {
        List<String> log = new ArrayList<>();
        DemoSeedProperties props = new DemoSeedProperties(false, TENANT, "pw", false, 1L);
        assertThat(runner(props, List.of("demo-seed"), List.of(step("a", 1, log, new UUID[1]))).execute())
                .isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(log).isEmpty();
    }

    @Test
    void runnerChayTheoThuTuTrongTenantContextRoiXoa() {
        List<String> log = new ArrayList<>();
        UUID[] seen = new UUID[1];
        DemoSeedProperties props = new DemoSeedProperties(true, TENANT, "pw", false, 1L);
        int code = runner(props, List.of("dev", "demo-seed"),
                List.of(step("sau", 2, log, seen), step("truoc", 1, log, seen))).execute();
        assertThat(code).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertThat(log).containsExactly("truoc", "sau");
        assertThat(seen[0]).isEqualTo(UUID.fromString(TENANT));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void runnerOIdentityChayKhongCoTenant() {
        List<String> log = new ArrayList<>();
        UUID[] seen = new UUID[]{UUID.randomUUID()};
        DemoSeedProperties props = new DemoSeedProperties(true, null, "pw", true, 1L);
        assertThat(runner(props, List.of("dev"), List.of(step("a", 1, log, seen))).execute()).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertThat(seen[0]).isNull();
    }

    @Test
    void loiMotBuocDungSeedVaTraMaLoi() {
        List<String> log = new ArrayList<>();
        DemoSeedStep boom = new DemoSeedStep() {
            @Override
            public int order() {
                return 1;
            }

            @Override
            public String name() {
                return "boom";
            }

            @Override
            public SeedResult run(DemoSeedContext context) {
                throw new IllegalStateException("hỏng");
            }
        };
        DemoSeedProperties props = new DemoSeedProperties(true, TENANT, "pw", false, 1L);
        int code = runner(props, List.of("dev"), List.of(boom, step("after", 2, log, new UUID[1]))).execute();
        assertThat(code).isEqualTo(DemoSeedRunner.EXIT_FAILED);
        assertThat(log).isEmpty();
    }

    @Test
    void runGoiExitHandlerVoiMaThoat() {
        AtomicInteger exit = new AtomicInteger(-1);
        DemoSeedProperties props = new DemoSeedProperties(true, TENANT, "pw", false, 1L);
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.refresh();
        DemoSeedRunner r = new DemoSeedRunner(props, List.of("prod"), List.of(), Clock.systemUTC(), ctx, exit::set);
        r.run(null);
        assertThat(exit.get()).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
    }
}
