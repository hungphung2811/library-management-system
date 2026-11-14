package lms.migration.application;

import lms.migration.domain.RunResult;

/** Chạy các script còn pending lên một DB đích. */
public interface MigrationRunnerPort {

    /** @throws lms.migration.domain.UnknownTargetException target không tồn tại
     *  @throws lms.migration.domain.MigrationExecutionException chạy lỗi */
    RunResult migrate(String target);
}
