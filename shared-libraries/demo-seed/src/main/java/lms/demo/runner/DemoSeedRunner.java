package lms.demo.runner;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import lms.demo.plan.DemoPlan;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Chạy các {@link DemoSeedStep} của service một lần rồi thoát tiến trình (ADR-0018 mục 2.4). Chỉ được tạo khi profile
 * {@code demo-seed} bật; mọi điều kiện an toàn do {@link DemoSeedGuard} kiểm trước khi ghi bất cứ thứ gì.
 * Mã thoát: 0 thành công, 2 bị guard từ chối, 1 lỗi ở một bước.
 */
public class DemoSeedRunner implements ApplicationRunner {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_REFUSED = 2;
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final DemoSeedProperties properties;
    private final Collection<String> activeProfiles;
    private final List<DemoSeedStep> steps;
    private final Clock clock;
    private final ApplicationContext applicationContext;
    private final IntConsumer exitHandler;

    public DemoSeedRunner(DemoSeedProperties properties, Collection<String> activeProfiles, List<DemoSeedStep> steps,
                          Clock clock, ApplicationContext applicationContext, IntConsumer exitHandler) {
        this.properties = properties;
        this.activeProfiles = List.copyOf(activeProfiles);
        this.steps = steps.stream().sorted(Comparator.comparingInt(DemoSeedStep::order)).toList();
        this.clock = clock;
        this.applicationContext = applicationContext;
        this.exitHandler = exitHandler;
    }

    public static DemoSeedRunner forApplication(DemoSeedProperties properties, Collection<String> activeProfiles,
                                                List<DemoSeedStep> steps, ApplicationContext context) {
        return new DemoSeedRunner(properties, activeProfiles, steps, Clock.system(BUSINESS_ZONE), context, System::exit);
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = execute();
        // đóng context (đóng datasource...) rồi mới thoát tiến trình với đúng mã
        int exitCode = applicationContext instanceof ConfigurableApplicationContext configurable
                ? SpringApplication.exit(configurable, () -> code)
                : code;
        exitHandler.accept(exitCode);
    }

    /** Guard rồi chạy từng bước; trả mã thoát. Không ném lỗi ra ngoài. */
    public int execute() {
        UUID tenantId;
        try {
            tenantId = DemoSeedGuard.check(activeProfiles, properties.enabled(), properties.tenantId(),
                    properties.createsTenant(), properties.password());
        } catch (DemoSeedRefusedException e) {
            log.error("{}", e.getMessage());
            return EXIT_REFUSED;
        }

        DemoPlan plan = DemoPlan.generate(LocalDate.now(clock), properties.seed());
        DemoSeedContext context = new DemoSeedContext(tenantId, plan, properties.password(), clock);
        int created = 0;
        int skipped = 0;
        for (DemoSeedStep step : steps) {
            try {
                DemoSeedStep.SeedResult result = tenantId != null
                        ? TenantContext.callAs(tenantId, () -> step.run(context))
                        : step.run(context);
                log.info("demo-seed step={} created={} skipped={}", step.name(), result.created(), result.skipped());
                created += result.created();
                skipped += result.skipped();
            } catch (RuntimeException e) {
                log.error("demo-seed step={} lỗi, dừng seed", step.name(), e);
                return EXIT_FAILED;
            }
        }
        log.info("demo-seed xong: steps={} created={} skipped={}", steps.size(), created, skipped);
        return EXIT_OK;
    }
}
