package lms.system.migration.infrastructure.web;

import lms.security.permission.PlatformAdminOnly;
import lms.system.migration.application.GetMigrationStatusUseCase;
import lms.system.migration.application.RunMigrationsUseCase;
import lms.system.migration.infrastructure.web.MigrationResponses.AllStatusResponse;
import lms.system.migration.infrastructure.web.MigrationResponses.RunAllResponse;
import lms.system.migration.infrastructure.web.MigrationResponses.RunResultResponse;
import lms.system.migration.infrastructure.web.MigrationResponses.TargetStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Migrate chạm mọi DB của mọi tenant nên chỉ quản trị nền tảng (claim platformAdmin) được gọi, không dùng khóa
 * quyền động (ADR-0007, Q1). Các khóa MigrationController/* cũ không còn được kiểm.
 */
@PlatformAdminOnly
@RestController
@RequestMapping("/internal/migrations")
public class MigrationController {

    private final GetMigrationStatusUseCase getStatus;
    private final RunMigrationsUseCase runMigrations;

    public MigrationController(GetMigrationStatusUseCase getStatus, RunMigrationsUseCase runMigrations) {
        this.getStatus = getStatus;
        this.runMigrations = runMigrations;
    }

    @GetMapping
    public AllStatusResponse statusAll() {
        return AllStatusResponse.from(getStatus.all());
    }

    @GetMapping("/{target}")
    public TargetStatusResponse statusOf(@PathVariable String target) {
        return TargetStatusResponse.from(getStatus.byTarget(target));
    }

    @PostMapping("/run")
    public RunAllResponse runAll() {
        return RunAllResponse.from(runMigrations.runAll());
    }

    @PostMapping("/{target}/run")
    public RunResultResponse runTarget(@PathVariable String target) {
        return RunResultResponse.from(runMigrations.runTarget(target));
    }
}
