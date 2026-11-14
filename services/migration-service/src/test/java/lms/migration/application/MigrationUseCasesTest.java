package lms.migration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import lms.migration.domain.MigrationScript;
import lms.migration.domain.MigrationState;
import lms.migration.domain.RunResult;
import lms.migration.domain.TargetMigrationStatus;
import lms.migration.domain.UnknownTargetException;
import org.junit.jupiter.api.Test;

class MigrationUseCasesTest {

    /** Port giả: mỗi target có số script pending; migrate chuyển hết sang APPLIED. */
    static class FakePorts implements MigrationRunnerPort, MigrationHistoryPort {
        final List<String> order = new ArrayList<>();
        final List<String> ran = new ArrayList<>();
        int pendingA = 2;

        @Override
        public List<String> targets() {
            return List.of("a", "b");
        }

        @Override
        public TargetMigrationStatus status(String target) {
            if (!targets().contains(target)) {
                throw new UnknownTargetException(target);
            }
            int pending = target.equals("a") ? pendingA : 0;
            List<MigrationScript> scripts = new ArrayList<>();
            scripts.add(script("1", MigrationState.APPLIED));
            for (int i = 0; i < pending; i++) {
                scripts.add(script("p" + i, MigrationState.PENDING));
            }
            return new TargetMigrationStatus(target, scripts);
        }

        @Override
        public RunResult migrate(String target) {
            status(target);
            ran.add(target);
            int executed = target.equals("a") ? pendingA : 0;
            if (target.equals("a")) {
                pendingA = 0;
            }
            return new RunResult(target, executed, "1");
        }

        private static MigrationScript script(String v, MigrationState s) {
            return new MigrationScript(v, "d", "V" + v + "__d.sql", "SQL", 1, s, null, null, null);
        }
    }

    @Test
    void trangThaiDemDungApplyVaPending() {
        var uc = new GetMigrationStatusUseCase(new FakePorts());

        var all = uc.all();

        assertThat(all).extracting(TargetMigrationStatus::target).containsExactly("a", "b");
        assertThat(all.get(0).applied()).isEqualTo(1);
        assertThat(all.get(0).pending()).isEqualTo(2);
        assertThat(all.get(1).pending()).isZero();
    }

    @Test
    void runAllChayTheoThuTuVaLanHaiKhongChayLai() {
        var ports = new FakePorts();
        var uc = new RunMigrationsUseCase(ports, ports);

        var first = uc.runAll();
        var second = uc.runAll();

        assertThat(ports.ran).containsExactly("a", "b", "a", "b");
        assertThat(first).extracting(RunResult::executed).containsExactly(2, 0);
        assertThat(second).extracting(RunResult::executed).containsExactly(0, 0);
    }

    @Test
    void targetLaBiTuChoi() {
        var ports = new FakePorts();

        assertThatThrownBy(() -> new RunMigrationsUseCase(ports, ports).runTarget("x"))
                .isInstanceOf(UnknownTargetException.class);
        assertThatThrownBy(() -> new GetMigrationStatusUseCase(ports).byTarget("x"))
                .isInstanceOf(UnknownTargetException.class);
    }
}
