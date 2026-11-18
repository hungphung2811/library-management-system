package lms.sales.shift;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.application.CashierShiftAdminUseCases;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftAttributionService;
import lms.sales.shift.application.ShiftLedgerPort;
import lms.sales.shift.application.ShiftOperations;
import lms.sales.shift.application.ShiftReport;
import lms.sales.shift.application.ShiftSettingsPort;
import lms.sales.shift.application.ShiftSettingsUseCases;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftSettings;
import lms.sales.support.Harness;

/**
 * Bộ giả trong bộ nhớ cho context shift: kho ca + cấu hình (cài ràng buộc "mỗi thu ngân một ca OPEN" như chỉ mục duy nhất
 * một phần), sổ cái tính từ đơn và khoản hoàn của {@link Harness} (đúng như truy vấn gom nhóm theo shift_id ở DB).
 */
public final class ShiftFixture {

    public final Harness h;
    public final InMemoryShifts store = new InMemoryShifts();
    public final OrdersLedger ledger;
    public final ShiftOperations operations;
    public final CashierShiftUseCases cashier;
    public final CashierShiftAdminUseCases admin;
    public final ShiftSettingsUseCases settings;
    public final ShiftAttributionService attribution;
    /** Đơn do test tạo, để sổ cái giả tìm bản ghi thu/hoàn theo ca. */
    public final List<UUID> trackedOrders = new ArrayList<>();

    public ShiftFixture(Harness h) {
        this.h = h;
        this.ledger = new OrdersLedger();
        this.operations = new ShiftOperations(store, ledger, store, h.numbers, h.auditPort, h.clock);
        this.cashier = new CashierShiftUseCases(store, operations);
        this.admin = new CashierShiftAdminUseCases(store, operations);
        this.settings = new ShiftSettingsUseCases(store, h.auditPort, h.clock);
        this.attribution = new ShiftAttributionService(store, store);
    }

    public CashierShiftUseCases.OpenCommand open(long openingFloat) {
        return new CashierShiftUseCases.OpenCommand(openingFloat, null, UUID.randomUUID());
    }

    public void setRequireOpenShift(boolean on) {
        ShiftSettings cur = store.find(h.tenant).orElse(ShiftSettings.defaults(h.tenant));
        store.save(cur.update(on, cur.varianceThreshold(), h.clock.instant(), h.user));
    }

    public void setThreshold(long threshold) {
        ShiftSettings cur = store.find(h.tenant).orElse(ShiftSettings.defaults(h.tenant));
        store.save(cur.update(cur.requireOpenShiftForPos(), threshold, h.clock.instant(), h.user));
    }

    /** Kho ca và cấu hình trong bộ nhớ. */
    public static final class InMemoryShifts implements CashierShiftRepository, ShiftSettingsPort {
        private final Map<UUID, CashierShift> shifts = new LinkedHashMap<>();
        private final List<ShiftEvent> events = new ArrayList<>();
        private final Map<UUID, ShiftSettings> settings = new LinkedHashMap<>();
        /** Nếu đặt: findOpenByCashier chờ ở rào này (mô phỏng hai yêu cầu cùng qua bước kiểm trước). */
        public volatile CyclicBarrier openCheckBarrier;

        @Override
        public synchronized CashierShift save(CashierShift s) {
            CashierShift existing = shifts.get(s.id());
            if (existing == null) {
                boolean openExists = s.isOpen() && shifts.values().stream().anyMatch(o -> o.isOpen()
                        && o.tenantId().equals(s.tenantId()) && o.cashierUserId().equals(s.cashierUserId()));
                if (openExists) { // uq_cashier_shifts_open
                    throw new SalesException(ErrorCode.SHIFT_ALREADY_OPEN, "Thu ngân đang có một ca mở");
                }
            } else if (existing.version() != s.version()) {
                throw new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "version lệch");
            }
            CashierShift stored = CashierShift.restore(new CashierShift.Snapshot(s.id(), s.tenantId(), s.shiftNo(),
                    s.cashierUserId(), s.registerLabel(), s.status(), s.openedAt(), s.openingFloat(), s.closedAt(),
                    s.closedBy(), s.countedCash(), s.expectedCash(), s.variance(), s.varianceReason(),
                    s.varianceStatus(), s.approvedBy(), s.approvedAt(), s.approvalNote(), s.reopenCount(),
                    s.clientRequestId(), existing == null ? 0 : existing.version() + 1));
            shifts.put(s.id(), stored);
            return stored;
        }

        @Override
        public synchronized Optional<CashierShift> findById(UUID tenantId, UUID id) {
            return Optional.ofNullable(shifts.get(id)).filter(s -> s.tenantId().equals(tenantId))
                    .map(InMemoryShifts::copy);
        }

        @Override
        public Optional<CashierShift> findOpenByCashier(UUID tenantId, UUID cashierUserId) {
            Optional<CashierShift> found;
            synchronized (this) {
                found = shifts.values().stream().filter(s -> s.tenantId().equals(tenantId) && s.isOpen()
                        && s.cashierUserId().equals(cashierUserId)).findFirst().map(InMemoryShifts::copy);
            }
            CyclicBarrier barrier = openCheckBarrier;
            if (barrier != null) {
                try {
                    barrier.await(); // cả hai luồng đã kiểm xong "chưa có ca mở" rồi mới cùng lưu
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return found;
        }

        @Override
        public synchronized Optional<CashierShift> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
            return shifts.values().stream().filter(s -> s.tenantId().equals(tenantId)
                    && s.clientRequestId().equals(clientRequestId)).findFirst().map(InMemoryShifts::copy);
        }

        @Override
        public synchronized PageResult<CashierShift> search(UUID tenantId, Criteria c, int page, int size) {
            List<CashierShift> all = shifts.values().stream().filter(s -> s.tenantId().equals(tenantId))
                    .filter(s -> c.cashierUserId() == null || c.cashierUserId().equals(s.cashierUserId()))
                    .filter(s -> c.status() == null || c.status() == s.status())
                    .filter(s -> c.varianceStatus() == null || c.varianceStatus() == s.varianceStatus())
                    .sorted((a, b) -> b.openedAt().compareTo(a.openedAt())).map(InMemoryShifts::copy).toList();
            int from = Math.min(page * size, all.size());
            return new PageResult<>(all.subList(from, Math.min(from + size, all.size())), all.size(), page, size);
        }

        @Override
        public synchronized void appendEvent(UUID tenantId, ShiftEvent event) {
            events.add(event);
        }

        @Override
        public synchronized List<ShiftEvent> events(UUID tenantId, UUID shiftId) {
            return events.stream().filter(e -> e.shiftId().equals(shiftId)).toList();
        }

        public synchronized List<ShiftEvent> allEvents() {
            return List.copyOf(events);
        }

        @Override
        public synchronized Optional<ShiftSettings> find(UUID tenantId) {
            return Optional.ofNullable(settings.get(tenantId));
        }

        @Override
        public synchronized ShiftSettings save(ShiftSettings s) {
            ShiftSettings cur = settings.get(s.tenantId());
            long curVersion = cur == null ? 0 : cur.version();
            if (curVersion != s.version()) {
                throw new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "version lệch");
            }
            ShiftSettings stored = ShiftSettings.restore(s.tenantId(), s.requireOpenShiftForPos(),
                    s.varianceThreshold(), curVersion + 1, s.updatedAt(), s.updatedBy());
            settings.put(s.tenantId(), stored);
            return stored;
        }

        private static CashierShift copy(CashierShift s) {
            return CashierShift.restore(new CashierShift.Snapshot(s.id(), s.tenantId(), s.shiftNo(),
                    s.cashierUserId(), s.registerLabel(), s.status(), s.openedAt(), s.openingFloat(), s.closedAt(),
                    s.closedBy(), s.countedCash(), s.expectedCash(), s.variance(), s.varianceReason(),
                    s.varianceStatus(), s.approvedBy(), s.approvedAt(), s.approvalNote(), s.reopenCount(),
                    s.clientRequestId(), s.version()));
        }
    }

    /** Sổ cái giả: gom bản ghi thu và khoản hoàn REFUNDED có shiftId của ca, theo phương thức. */
    public final class OrdersLedger implements ShiftLedgerPort {
        @Override
        public ShiftReport report(UUID tenantId, UUID shiftId, Instant closedAt) {
            Map<String, long[]> by = new LinkedHashMap<>();
            for (String m : List.of("CASH", "CARD", "BANK_TRANSFER", "QR", "MOMO", "SEPAY")) {
                by.put(m, new long[8]);
            }
            long orders = 0;
            long grand = 0;
            long discount = 0;
            Instant mark = closedAt == null ? Instant.MAX : closedAt;
            for (UUID id : trackedOrders) {
                SalesOrder o = h.orderRepo.findById(tenantId, id).orElseThrow();
                boolean counted = false;
                for (var p : o.payments()) {
                    if (shiftId.equals(p.shiftId())) {
                        long[] a = by.computeIfAbsent(p.method().name(), k -> new long[8]);
                        a[0]++;
                        a[1] += p.amount().amount();
                        if (p.recordedAt().isAfter(mark)) {
                            a[4]++;
                            a[5] += p.amount().amount();
                        }
                        counted = true;
                    }
                }
                if (counted) {
                    orders++;
                    grand += o.grandTotal().amount();
                    discount += o.discountTotal().amount();
                }
                for (var r : h.refundRepo.findByOrderId(tenantId, id)) {
                    if (r.status() == RefundStatus.REFUNDED && shiftId.equals(r.shiftId())) {
                        long[] a = by.computeIfAbsent(r.method().name(), k -> new long[8]);
                        a[2]++;
                        a[3] += r.amount().amount();
                        if (r.refundedAt().isAfter(mark)) {
                            a[6]++;
                            a[7] += r.amount().amount();
                        }
                    }
                }
            }
            List<ShiftReport.MethodLine> lines = new ArrayList<>();
            long collected = 0;
            long refunded = 0;
            for (var e : by.entrySet()) {
                long[] a = e.getValue();
                lines.add(new ShiftReport.MethodLine(e.getKey(), a[0], a[1], a[2], a[3], a[1] - a[3], a[4], a[5],
                        a[6], a[7]));
                collected += a[1];
                refunded += a[3];
            }
            return new ShiftReport(orders, grand, discount, lines, collected, refunded, collected - refunded);
        }

        @Override
        public long pendingManualIntents(UUID tenantId, UUID shiftId, Instant now) {
            return trackedOrders.stream().map(id -> h.intents.findByOrderId(tenantId, id)).flatMap(Optional::stream)
                    .filter(i -> shiftId.equals(i.shiftId()) && i.status() == PaymentIntentStatus.PENDING
                            && "MANUAL".equals(i.provider()) && i.expiresAt().isAfter(now)).count();
        }
    }

    /** Phương thức tiện cho test: dùng PaymentMethod ở nơi gọi. */
    public static PaymentMethod cash() {
        return PaymentMethod.CASH;
    }
}
