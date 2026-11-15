package lms.system.migration.application;

import java.util.List;
import lms.system.migration.domain.RunResult;

public class RunMigrationsUseCase {

    private final MigrationRunnerPort runner;
    private final MigrationHistoryPort history;

    public RunMigrationsUseCase(MigrationRunnerPort runner, MigrationHistoryPort history) {
        this.runner = runner;
        this.history = history;
    }

    public RunResult runTarget(String target) {
        return runner.migrate(target);
    }

    /** Chạy lần lượt mọi target theo thứ tự cấu hình; dừng ở target lỗi đầu tiên. */
    public List<RunResult> runAll() {
        return history.targets().stream().map(runner::migrate).toList();
    }
}
