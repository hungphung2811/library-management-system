package lms.sales.shift.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftEventType;
import lms.sales.shift.domain.ShiftSettings;

/**
 * Các thao tác trên ca dùng chung cho thu ngân (ca của mình) và quản lý (mọi ca): mở, đóng, mở lại, duyệt, dựng chi tiết.
 * Không tự mở transaction: người gọi ({@link CashierShiftUseCases}, {@link CashierShiftAdminUseCases}) là điểm
 * {@code @Transactional}, nên số ca, ghi ca, lịch sử và audit nằm trong MỘT transaction của sales_db.
 * Audit chỉ mang id, số tiền, mã trạng thái (lý do tự do nằm ở lịch sử ca, không vào audit/Kafka).
 */
public final class ShiftOperations {

    private final CashierShiftRepository shifts;
    private final ShiftLedgerPort ledger;
    private final ShiftSettingsPort settings;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final Clock clock;

    public ShiftOperations(CashierShiftRepository shifts, ShiftLedgerPort ledger, ShiftSettingsPort settings,
                           DocumentNumberPort numbers, SalesAuditPort audit, Clock clock) {
        this.shifts = shifts;
        this.ledger = ledger;
        this.settings = settings;
        this.numbers = numbers;
        this.audit = audit;
        this.clock = clock;
    }

    /** Mở ca cho {@code cashier}. Gửi lại cùng clientRequestId của chính người đó thì trả ca cũ (idempotent). */
    CashierShift open(UUID tenantId, UUID cashier, long openingFloat, String registerLabel, UUID clientRequestId) {
        var replay = clientRequestId == null ? java.util.Optional.<CashierShift>empty()
                : shifts.findByClientRequestId(tenantId, clientRequestId);
        if (replay.isPresent()) {
            if (!replay.get().isOwnedBy(cashier)) {
                throw SalesException.invalidRequest("clientRequestId đã được dùng");
            }
            return replay.get();
        }
        if (cashier != null && shifts.findOpenByCashier(tenantId, cashier).isPresent()) {
            throw new SalesException(ErrorCode.SHIFT_ALREADY_OPEN, "Thu ngân đang có một ca mở");
        }
        Instant now = clock.instant();
        CashierShift shift = CashierShift.open(tenantId, numbers.next(tenantId, DocumentNumberPort.SHIFT), cashier,
                registerLabel, openingFloat, clientRequestId, now);
        CashierShift saved = shifts.save(shift);
        shifts.appendEvent(tenantId, event(saved, ShiftEventType.OPENED, cashier, now,
                Map.of("openingFloat", saved.openingFloat())));
        record(tenantId, SalesAuditPort.Actions.SHIFT_OPENED, saved, cashier, now,
                Map.of("shiftNo", saved.shiftNo(), "openingFloat", saved.openingFloat()));
        return saved;
    }

    /**
     * Đóng ca: không còn lệnh thu tay PENDING của ca (409 SHIFT_HAS_PENDING_PAYMENTS, lệnh tự hết hạn sau 10 phút), tính
     * tiền mặt dự kiến từ tiền đã vào ca, chênh lệch so với tiền đếm, kiểm lý do theo ngưỡng của tenant.
     */
    CashierShift close(UUID tenantId, UUID actor, CashierShift shift, long countedCash, String reason) {
        if (!shift.isOpen()) {
            throw new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "Ca không đang mở");
        }
        Instant now = clock.instant();
        long pending = ledger.pendingManualIntents(tenantId, shift.id(), now);
        if (pending > 0) {
            throw new SalesException(ErrorCode.SHIFT_HAS_PENDING_PAYMENTS,
                    "Ca còn " + pending + " lệnh thanh toán chờ xác nhận, hãy xác nhận hoặc hủy trước khi đóng ca",
                    Map.of("pendingPayments", pending));
        }
        ShiftReport report = ledger.report(tenantId, shift.id(), null);
        long expected = CashierShift.expectedCash(shift.openingFloat(), report.cashCollected(),
                report.cashRefunded());
        long threshold = settings.find(tenantId).map(ShiftSettings::varianceThreshold).orElse(0L);
        shift.close(countedCash, expected, reason, threshold, actor, now);
        CashierShift saved = shifts.save(shift);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("countedCash", saved.countedCash());
        snapshot.put("expectedCash", saved.expectedCash());
        snapshot.put("variance", saved.variance());
        snapshot.put("varianceStatus", saved.varianceStatus().name());
        snapshot.put("varianceReason", saved.varianceReason());
        snapshot.put("closedOnBehalf", !saved.isOwnedBy(actor));
        shifts.appendEvent(tenantId, event(saved, ShiftEventType.CLOSED, actor, now, snapshot));
        record(tenantId, SalesAuditPort.Actions.SHIFT_CLOSED, saved, actor, now,
                Map.of("shiftNo", saved.shiftNo(), "countedCash", saved.countedCash(),
                        "expectedCash", saved.expectedCash(), "variance", saved.variance(),
                        "varianceStatus", saved.varianceStatus().name(),
                        "closedOnBehalf", !saved.isOwnedBy(actor)));
        return saved;
    }

    /** Mở lại ca đã đóng (quản lý, lý do bắt buộc). Thu ngân đang có ca mở khác thì 409 SHIFT_ALREADY_OPEN. */
    CashierShift reopen(UUID tenantId, UUID actor, CashierShift shift, String reason) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("reason", reason == null ? null : reason.trim());
        before.put("previousCountedCash", shift.countedCash());
        before.put("previousExpectedCash", shift.expectedCash());
        before.put("previousVariance", shift.variance());
        shift.reopen(reason); // kiểm trạng thái và lý do trước khi tra ca mở khác
        if (shifts.findOpenByCashier(tenantId, shift.cashierUserId()).filter(o -> !o.id().equals(shift.id()))
                .isPresent()) {
            throw new SalesException(ErrorCode.SHIFT_ALREADY_OPEN, "Thu ngân đang có một ca mở khác");
        }
        Instant now = clock.instant();
        CashierShift saved = shifts.save(shift);
        shifts.appendEvent(tenantId, event(saved, ShiftEventType.REOPENED, actor, now, before));
        record(tenantId, SalesAuditPort.Actions.SHIFT_REOPENED, saved, actor, now,
                Map.of("shiftNo", saved.shiftNo(), "reopenCount", saved.reopenCount()));
        return saved;
    }

    CashierShift approveVariance(UUID tenantId, UUID actor, CashierShift shift, String note) {
        Instant now = clock.instant();
        shift.approveVariance(note, actor, now);
        CashierShift saved = shifts.save(shift);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("variance", saved.variance());
        snapshot.put("note", saved.approvalNote());
        shifts.appendEvent(tenantId, event(saved, ShiftEventType.VARIANCE_APPROVED, actor, now, snapshot));
        record(tenantId, SalesAuditPort.Actions.SHIFT_VARIANCE_APPROVED, saved, actor, now,
                Map.of("shiftNo", saved.shiftNo(), "variance", saved.variance()));
        return saved;
    }

    /** Chi tiết: ca, báo cáo theo phương thức (số câu SQL cố định) và lịch sử. */
    ShiftDetailView detail(UUID tenantId, CashierShift shift) {
        ShiftReport report = ledger.report(tenantId, shift.id(), shift.closedAt());
        Long provisional = shift.isOpen() ? CashierShift.expectedCash(shift.openingFloat(), report.cashCollected(),
                report.cashRefunded()) : null;
        List<ShiftDetailView.EventView> history = shifts.events(tenantId, shift.id()).stream()
                .map(ShiftDetailView.EventView::from).toList();
        return new ShiftDetailView(ShiftView.from(shift), report, provisional, history);
    }

    private static ShiftEvent event(CashierShift s, ShiftEventType type, UUID actor, Instant at,
                                    Map<String, Object> snapshot) {
        return new ShiftEvent(UUID.randomUUID(), s.id(), type, actor, at, snapshot);
    }

    private void record(UUID tenantId, String action, CashierShift s, UUID actor, Instant at,
                        Map<String, Object> data) {
        audit.record(new SalesAuditPort.AuditEntry(tenantId, action, "CashierShift", s.id(), actor, at, data));
    }
}
