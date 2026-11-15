package lms.system.migration.application;

import lms.system.migration.domain.RunResult;

/** Chạy các script còn pending lên một DB đích. */
public interface MigrationRunnerPort {

    /** @throws lms.system.migration.domain.UnknownTargetException target không tồn tại
     *  @throws lms.system.migration.domain.MigrationExecutionException chạy lỗi */
    RunResult migrate(String target);
}
