package lms.sales.shift.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.ShiftLedgerPort;
import lms.sales.shift.application.ShiftReport;
import lms.sales.shift.application.ShiftSettingsPort;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftEventType;
import lms.sales.shift.domain.ShiftSettings;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed ca thu ngân của tenant demo (ADR-0030 gói B, ADR-0018): cấu hình ca (ngưỡng chênh lệch 20.000), năm ca đã đóng
 * ở các ngày trước và một ca đang mở hôm nay, đủ trạng thái chênh lệch (khớp, thừa trong ngưỡng, thiếu đã duyệt, thiếu
 * đang chờ duyệt). Bản ghi thu của đơn POS trong ngày được gắn vào ca của ngày đó (cập nhật
 * {@code payment_records.shift_id} còn trống theo id tất định của bước đơn hàng), nên báo cáo theo phương thức của ca
 * khớp dữ liệu thật; tiền dự kiến lấy từ chính báo cáo đó.
 * <p>
 * Idempotent theo id ca tất định {@code DemoIds.of("shift", n)}: ca đã có thì bỏ qua cả phần gắn tiền. Cần bước
 * {@code sales.ordering} chạy trước (order 30). Ca không có đơn POS trong ngày vẫn được tạo (dự kiến bằng tiền đầu ca).
 */
@Component
@Profile("demo-seed")
public class ShiftDemoSeedStep implements DemoSeedStep {

    static final long VARIANCE_THRESHOLD = 20_000L;
    private static final int OPEN_MINUTE = 480;
    private static final int CLOSE_MINUTE = 1260;

    /** daysAgo, chỉ số nhân sự thu ngân, quầy, tiền đầu ca, chênh lệch (đếm - dự kiến), lý do, duyệt chênh lệch. */
    record Spec(int daysAgo, int staffIndex, String register, long openingFloat, long delta, String reason,
                boolean approve) {
        boolean open() {
            return daysAgo == 0;
        }
    }

    static final List<Spec> SPECS = List.of(
            new Spec(5, 2, "Quầy 1", 500_000, 0, null, false),
            new Spec(4, 0, "Quầy 2", 500_000, -30_000, "Trả nhầm tiền thừa cho khách (dữ liệu demo)", true),
            new Spec(3, 2, "Quầy 1", 500_000, 10_000, null, false),
            new Spec(2, 2, "Quầy 1", 500_000, -50_000, "Chưa rõ nguyên nhân, chờ quản lý kiểm tra (dữ liệu demo)",
                    false),
            new Spec(1, 0, "Quầy 2", 300_000, 0, null, false),
            new Spec(0, 2, "Quầy 1", 500_000, 0, null, false));

    private final CashierShiftRepository shifts;
    private final ShiftSettingsPort settings;
    private final ShiftLedgerPort ledger;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;

    public ShiftDemoSeedStep(CashierShiftRepository shifts, ShiftSettingsPort settings, ShiftLedgerPort ledger,
                             JdbcTemplate jdbc, TransactionOperations tx) {
        this.shifts = shifts;
        this.settings = settings;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 45;
    }

    @Override
    public String name() {
        return "sales.shift";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        Clock clock = context.clock();
        int created = 0;
        int skipped = 0;

        if (settings.find(tenantId).isPresent()) {
            skipped++;
        } else {
            tx.executeWithoutResult(s -> settings.save(ShiftSettings.defaults(tenantId)
                    .update(false, VARIANCE_THRESHOLD, clock.instant(), plan.staff().get(2).id())));
            created++;
        }

        for (int i = 0; i < SPECS.size(); i++) {
            Spec spec = SPECS.get(i);
            UUID id = DemoIds.of("shift", i + 1);
            if (shifts.findById(tenantId, id).isPresent()) {
                skipped++;
                continue;
            }
            seedShift(tenantId, plan, clock, spec, i, id);
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    private void seedShift(UUID tenantId, DemoPlan plan, Clock clock, Spec spec, int i, UUID id) {
        UUID cashier = plan.staff().get(spec.staffIndex()).id();
        Instant openedAt = DemoTimes.clamp(DemoTimes.at(plan.today(), spec.daysAgo(), OPEN_MINUTE), clock);
        String shiftNo = String.format("DEMO-CA-%04d", i + 1);
        CashierShift opened = CashierShift.restore(new CashierShift.Snapshot(id, tenantId, shiftNo, cashier,
                spec.register(), ShiftStatus.OPEN, openedAt, spec.openingFloat(), null, null, null, null, null, null,
                VarianceStatus.NONE, null, null, null, 0, DemoIds.of("request:shift", i + 1), 0));
        tx.executeWithoutResult(s -> {
            shifts.save(opened);
            shifts.appendEvent(tenantId, new ShiftEvent(DemoIds.of("shift-event", (i + 1) * 10 + 1), id,
                    ShiftEventType.OPENED, cashier, openedAt, Map.of("openingFloat", spec.openingFloat())));
        });

        // Gắn tiền của đơn POS trong ngày vào ca (chỉ dòng chưa thuộc ca nào)
        List<UUID> paymentIds = new ArrayList<>();
        for (OrderPlan o : plan.orders()) {
            if (o.channel() == DemoPlan.Channel.POS && o.isPaid() && o.createdDaysAgo() == spec.daysAgo()) {
                paymentIds.add(DemoIds.of("payment", o.index()));
            }
        }
        if (!paymentIds.isEmpty()) {
            String in = String.join(",", Collections.nCopies(paymentIds.size(), "?"));
            Object[] args = new Object[paymentIds.size() + 2];
            args[0] = id;
            args[1] = tenantId;
            for (int k = 0; k < paymentIds.size(); k++) {
                args[k + 2] = paymentIds.get(k);
            }
            tx.executeWithoutResult(s -> jdbc.update("UPDATE payment_records SET shift_id = ? WHERE tenant_id = ? "
                    + "AND shift_id IS NULL AND id IN (" + in + ")", args));
        }
        if (spec.open()) {
            return;
        }

        Instant closedAt = DemoTimes.clamp(DemoTimes.at(plan.today(), spec.daysAgo(), CLOSE_MINUTE), clock);
        tx.executeWithoutResult(s -> {
            CashierShift shift = shifts.findById(tenantId, id).orElseThrow();
            ShiftReport report = ledger.report(tenantId, id, null);
            long expected = CashierShift.expectedCash(shift.openingFloat(), report.cashCollected(),
                    report.cashRefunded());
            shift.close(Math.max(0, expected + spec.delta()), expected, spec.reason(), VARIANCE_THRESHOLD, cashier,
                    closedAt);
            CashierShift saved = shifts.save(shift);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("countedCash", saved.countedCash());
            snapshot.put("expectedCash", saved.expectedCash());
            snapshot.put("variance", saved.variance());
            snapshot.put("varianceStatus", saved.varianceStatus().name());
            snapshot.put("varianceReason", saved.varianceReason());
            snapshot.put("closedOnBehalf", false);
            shifts.appendEvent(tenantId, new ShiftEvent(DemoIds.of("shift-event", (i + 1) * 10 + 2), id,
                    ShiftEventType.CLOSED, cashier, closedAt, snapshot));
            if (spec.approve() && saved.varianceStatus() == VarianceStatus.PENDING_APPROVAL) {
                UUID manager = plan.staff().get(0).id();
                Instant approvedAt = DemoTimes.plusClamped(closedAt, Duration.ofHours(2), clock);
                saved.approveVariance("Đã đối chiếu camera quầy, chấp nhận chênh lệch (dữ liệu demo)", manager,
                        approvedAt);
                shifts.save(saved);
                shifts.appendEvent(tenantId, new ShiftEvent(DemoIds.of("shift-event", (i + 1) * 10 + 3), id,
                        ShiftEventType.VARIANCE_APPROVED, manager, approvedAt,
                        Map.of("variance", saved.variance(), "note", saved.approvalNote())));
            }
        });
    }
}
