package lms.support.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep.SeedResult;
import lms.support.audit.application.RecordAuditEventUseCase;
import lms.support.audit.domain.AuditLog;
import lms.support.audit.infrastructure.demo.AuditDemoSeedStep;
import org.junit.jupiter.api.Test;

/** Bước seed audit (ADR-0018): đủ loại sự kiện, không mốc tương lai, chạy lại không nhân đôi (cổng lưu giả). */
class AuditDemoSeedStepTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private final Map<UUID, AuditLog> saved = new HashMap<>();
    private final Clock clock = Clock.system(VN);
    private final AuditDemoSeedStep step = new AuditDemoSeedStep(new RecordAuditEventUseCase(
            log -> saved.putIfAbsent(log.eventId(), log) == null, clock));

    private DemoSeedContext context() {
        return new DemoSeedContext(TENANT, DemoPlan.generate(LocalDate.now(VN)), "x", clock);
    }

    @Test
    void ghiDuLoaiSuKienKhongTuongLaiVaIdempotent() {
        SeedResult first = step.run(context());
        assertThat(first.created()).isGreaterThanOrEqualTo(40);
        assertThat(first.skipped()).isZero();
        assertThat(saved).hasSize(first.created());
        assertThat(saved.values()).extracting(AuditLog::eventType).contains("UserCreated", "RoleAssigned",
                "TitleCreated", "BorrowingCreated", "BookReturned", "OrderPlaced", "OrderCompleted", "FinePaid");
        assertThat(saved.values()).allSatisfy(l -> assertThat(l.occurredAt()).isBeforeOrEqualTo(clock.instant()));

        SeedResult second = step.run(context());
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(first.created());
        assertThat(saved).hasSize(first.created());
    }
}
