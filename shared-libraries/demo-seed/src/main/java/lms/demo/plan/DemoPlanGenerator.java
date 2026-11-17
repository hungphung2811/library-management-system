package lms.demo.plan;

import static lms.demo.plan.DemoPlan.BorrowingPlan;
import static lms.demo.plan.DemoPlan.Channel;
import static lms.demo.plan.DemoPlan.CopyPlan;
import static lms.demo.plan.DemoPlan.CopyStatus;
import static lms.demo.plan.DemoPlan.FinePlan;
import static lms.demo.plan.DemoPlan.FineStatus;
import static lms.demo.plan.DemoPlan.LoanItemPlan;
import static lms.demo.plan.DemoPlan.OrderLinePlan;
import static lms.demo.plan.DemoPlan.OrderPlan;
import static lms.demo.plan.DemoPlan.OrderStatus;
import static lms.demo.plan.DemoPlan.PaymentStatus;
import static lms.demo.plan.DemoPlan.ReaderPlan;
import static lms.demo.plan.DemoPlan.ReceiptLinePlan;
import static lms.demo.plan.DemoPlan.ReceiptPlan;
import static lms.demo.plan.DemoPlan.RefundPlan;
import static lms.demo.plan.DemoPlan.RefundSource;
import static lms.demo.plan.DemoPlan.RefundStatus;
import static lms.demo.plan.DemoPlan.ReservationPlan;
import static lms.demo.plan.DemoPlan.SalesReturnPlan;
import static lms.demo.plan.DemoPlan.StaffPlan;
import static lms.demo.plan.DemoPlan.StaffRole;
import static lms.demo.plan.DemoPlan.StockItemPlan;
import static lms.demo.plan.DemoPlan.SupplierPlan;
import static lms.demo.plan.DemoPlan.TitlePlan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bộ sinh {@link DemoPlan}. Mọi quyết định ngẫu nhiên đi qua {@link Random} có hạt giống cố định; số lần rút của luồng
 * chính chỉ phụ thuộc chỉ số cấu trúc, không phụ thuộc {@code today}, nên id/người mượn/bản sao đang mượn giống nhau
 * giữa các ngày chạy. Riêng độ lệch ngày mượn của lịch sử dùng trọng số cuối tuần theo {@code today}, và việc chọn bản sao
 * cho dòng đã trả (tránh trùng khoảng thời gian) dùng luồng ngẫu nhiên riêng.
 */
final class DemoPlanGenerator {

    static final int TITLES = 64;
    static final int COPIES = 160;
    static final int READERS = 24;
    static final int STOCK_ITEMS = 32;
    static final int ORDERS = 42;
    static final int HISTORY_DAYS = 60;
    static final int ORDER_DAYS = 30;

    private static final int LOANED_COPIES = 20;
    private static final int RESERVED_COPIES = 6;
    private static final int LOST_COPIES = 4;
    private static final int DAMAGED_COPIES = 5;
    private static final int[] OVERDUE_ITEM_COUNTS = {1, 2, 1, 1, 2, 1};
    private static final int OPEN_NOT_OVERDUE = 12;
    private static final int RETURNED_BORROWINGS = 112;
    private static final int[] UNPAID_FINE_HELD_DAYS = {19, 22, 26, 29};
    private static final int PAID_FINES = 7;

    private static final String[] TOPICS = {"Lập trình", "Lịch sử Việt Nam", "Văn học", "Kinh tế", "Khoa học", "Thiếu nhi",
            "Tâm lý học", "Nghệ thuật"};
    private static final String[] FORMS = {"Nhập môn", "Cẩm nang", "Những câu chuyện về", "Chuyên đề", "Tuyển tập",
            "Góc nhìn mới về", "Hành trình khám phá", "Bài giảng"};
    private static final String[] AUTHORS = {"Nguyễn Văn An", "Trần Thị Bình", "Lê Hoàng Cường", "Phạm Minh Đức",
            "Hoàng Thu Hà", "Vũ Quang Huy", "Đặng Khánh Linh", "Bùi Ngọc Mai", "Đỗ Thanh Nam", "Ngô Phương Oanh",
            "Dương Hải Phong", "Lý Gia Quân", "Phan Thảo Quyên", "Trịnh Anh Sơn", "Võ Thùy Trang", "Mai Xuân Vinh"};
    private static final String[] LAST_NAMES = {"Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Vũ"};
    private static final String[] MIDDLE_NAMES = {"Văn", "Thị", "Minh", "Ngọc"};
    private static final String[] FIRST_NAMES = {"An", "Bình", "Chi", "Dũng", "Giang", "Hạnh", "Khoa", "Lan"};

    private DemoPlanGenerator() {
    }

    static DemoPlan generate(LocalDate today, long seed) {
        Random rnd = new Random(seed);
        Random copyRnd = new Random(seed ^ 0x5DEECE66DL);

        List<StaffPlan> staff = staff();
        List<ReaderPlan> readers = readers();
        List<TitlePlan> titles = titles(rnd);
        List<CopyPlan> copies = copies(rnd);

        // ---- Mượn trả ----
        List<Integer> loanedIdx = new ArrayList<>();
        List<Integer> reservedIdx = new ArrayList<>();
        List<Integer> availableIdx = new ArrayList<>();
        for (CopyPlan c : copies) {
            switch (c.status()) {
                case LOANED -> loanedIdx.add(c.index());
                case RESERVED -> reservedIdx.add(c.index());
                case AVAILABLE -> availableIdx.add(c.index());
                default -> { }
            }
        }
        Deque<Integer> loanedQueue = new ArrayDeque<>(loanedIdx);
        List<BorrowingPlan> borrowings = new ArrayList<>();
        List<FinePlan> fines = new ArrayList<>();
        Map<Integer, List<int[]>> usage = new HashMap<>();

        int index = 0;
        for (int k = 0; k < OVERDUE_ITEM_COUNTS.length; k++) {
            int borrowDaysAgo = k == 0 ? DemoPlan.LOAN_DAYS + 1 : DemoPlan.LOAN_DAYS + 2 + rnd.nextInt(25);
            int reader = rnd.nextInt(READERS);
            List<LoanItemPlan> items = new ArrayList<>();
            for (int n = 0; n < OVERDUE_ITEM_COUNTS[k]; n++) {
                items.add(openItem(copies, loanedQueue.poll()));
            }
            borrowings.add(borrowing(today, index++, readers.get(reader), borrowDaysAgo, items));
        }
        for (int k = 0; k < OPEN_NOT_OVERDUE; k++) {
            int draw = rnd.nextInt(DemoPlan.LOAN_DAYS + 1);
            int borrowDaysAgo = k == 0 ? DemoPlan.LOAN_DAYS : k == 1 ? 0 : draw;
            int reader = rnd.nextInt(READERS);
            borrowings.add(borrowing(today, index++, readers.get(reader), borrowDaysAgo,
                    List.of(openItem(copies, loanedQueue.poll()))));
        }
        for (int r = 0; r < RETURNED_BORROWINGS; r++) {
            int normalHeld = 1 + rnd.nextInt(DemoPlan.LOAN_DAYS);
            int lateHeld = DemoPlan.LOAN_DAYS + 1 + rnd.nextInt(6);
            int held = r < UNPAID_FINE_HELD_DAYS.length ? UNPAID_FINE_HELD_DAYS[r]
                    : r < UNPAID_FINE_HELD_DAYS.length + PAID_FINES ? lateHeld : normalHeld;
            boolean late = r < UNPAID_FINE_HELD_DAYS.length + PAID_FINES;
            int borrowDaysAgo = weightedDay(rnd, today, held, HISTORY_DAYS - 1);
            int returnedDaysAgo = borrowDaysAgo - held;
            int reader = rnd.nextInt(READERS);
            boolean two = rnd.nextDouble() < 0.25 && !late;
            int copyCount = two ? 2 : 1;
            List<LoanItemPlan> items = new ArrayList<>();
            for (int n = 0; n < copyCount; n++) {
                int copyIdx = pickFreeCopy(copyRnd, availableIdx, usage, borrowDaysAgo, returnedDaysAgo);
                usage.computeIfAbsent(copyIdx, x -> new ArrayList<>()).add(new int[]{borrowDaysAgo, returnedDaysAgo});
                CopyPlan c = copies.get(copyIdx);
                items.add(new LoanItemPlan(c.id(), c.titleId(), returnedDaysAgo, today.minusDays(returnedDaysAgo)));
            }
            BorrowingPlan b = borrowing(today, index++, readers.get(reader), borrowDaysAgo, items);
            borrowings.add(b);
            if (late) {
                int lateDays = held - DemoPlan.LOAN_DAYS;
                boolean unpaid = r < UNPAID_FINE_HELD_DAYS.length;
                Integer paidDaysAgo = unpaid ? null : Math.max(0, returnedDaysAgo - rnd.nextInt(4));
                fines.add(new FinePlan(DemoIds.fine(fines.size()), fines.size(), b.id(), b.readerId(),
                        lateDays * DemoPlan.FINE_PER_DAY, unpaid ? FineStatus.UNPAID : FineStatus.PAID,
                        lateDays, returnedDaysAgo, paidDaysAgo));
            }
        }

        List<ReservationPlan> reservations = new ArrayList<>();
        for (int idx : reservedIdx) {
            CopyPlan c = copies.get(idx);
            int reader = rnd.nextInt(READERS);
            int createdDaysAgo = rnd.nextInt(3);
            reservations.add(new ReservationPlan(DemoIds.reservation(reservations.size()), reservations.size(),
                    readers.get(reader).id(), c.titleId(), c.id(), createdDaysAgo, 3));
        }

        // ---- Bán hàng ----
        long[] prices = new long[STOCK_ITEMS];
        for (int i = 0; i < STOCK_ITEMS; i++) {
            prices[i] = 50_000L + 5_000L * rnd.nextInt(31);
        }
        int[][] stockTargets = stockTargets(rnd);

        List<OrderPlan> orders = new ArrayList<>();
        List<SalesReturnPlan> returns = new ArrayList<>();
        List<RefundPlan> refunds = new ArrayList<>();
        int[] sold = new int[STOCK_ITEMS];
        int[] returned = new int[STOCK_ITEMS];
        for (int i = 0; i < ORDERS; i++) {
            buildOrder(rnd, i, titles, prices, sold, returned, orders, returns, refunds);
        }

        List<SupplierPlan> suppliers = List.of(
                new SupplierPlan(DemoIds.supplier(0), 0, "Nhà sách Phương Nam (demo)", "Trần Thị Thu", "phuongnam@" + DemoPlan.EMAIL_DOMAIN),
                new SupplierPlan(DemoIds.supplier(1), 1, "Công ty Phát hành Sách Tri Thức (demo)", "Lê Văn Hùng", "trithuc@" + DemoPlan.EMAIL_DOMAIN));
        List<StockItemPlan> stockItems = new ArrayList<>();
        List<List<ReceiptLinePlan>> receiptLines = List.of(new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < STOCK_ITEMS; i++) {
            int onHand = stockTargets[i][0];
            int reserved = stockTargets[i][1];
            Integer threshold = stockTargets[i][2] < 0 ? null : stockTargets[i][2];
            int received = onHand + sold[i] - returned[i];
            int receiptIndex = i % 2;
            stockItems.add(new StockItemPlan(DemoIds.title(i), i, onHand, reserved, threshold, prices[i], received,
                    sold[i], returned[i], receiptIndex));
            receiptLines.get(receiptIndex).add(new ReceiptLinePlan(DemoIds.title(i), received, prices[i] * 60 / 100));
        }
        List<ReceiptPlan> receipts = List.of(
                new ReceiptPlan(DemoIds.receipt(0), 0, 0, 50, receiptLines.get(0)),
                new ReceiptPlan(DemoIds.receipt(1), 1, 1, 45, receiptLines.get(1)));

        return new DemoPlan(today, seed, staff, readers, titles, copies, borrowings, reservations, fines, suppliers,
                receipts, stockItems, orders, returns, refunds);
    }

    // ---- Con người, danh mục ----

    private static List<StaffPlan> staff() {
        return List.of(
                new StaffPlan(DemoIds.staff(0), 0, StaffRole.TENANT_ADMIN, "demo.admin", "Quản trị Demo", "demo.admin@" + DemoPlan.EMAIL_DOMAIN),
                new StaffPlan(DemoIds.staff(1), 1, StaffRole.LIBRARIAN, "demo.thuthu", "Thủ thư Demo", "demo.thuthu@" + DemoPlan.EMAIL_DOMAIN),
                new StaffPlan(DemoIds.staff(2), 2, StaffRole.STORE_MANAGER, "demo.cuahang", "Quản lý cửa hàng Demo", "demo.cuahang@" + DemoPlan.EMAIL_DOMAIN));
    }

    private static List<ReaderPlan> readers() {
        List<ReaderPlan> list = new ArrayList<>();
        for (int i = 0; i < READERS; i++) {
            String name = LAST_NAMES[i % LAST_NAMES.length] + " " + MIDDLE_NAMES[(i / LAST_NAMES.length) % MIDDLE_NAMES.length]
                    + " " + FIRST_NAMES[(i * 3) % FIRST_NAMES.length];
            String username = String.format("demo.docgia%02d", i + 1);
            list.add(new ReaderPlan(DemoIds.reader(i), i, username, name, username + "@" + DemoPlan.EMAIL_DOMAIN,
                    String.format("DEMO-R%03d", i + 1)));
        }
        return list;
    }

    private static List<TitlePlan> titles(Random rnd) {
        List<TitlePlan> list = new ArrayList<>();
        for (int i = 0; i < TITLES; i++) {
            String category = TOPICS[i / FORMS.length];
            String name = FORMS[i % FORMS.length] + " " + category;
            int year = 1990 + rnd.nextInt(35);
            list.add(new TitlePlan(DemoIds.title(i), i, name, AUTHORS[(i * 5) % AUTHORS.length], isbn(i), category, year));
        }
        return list;
    }

    /** ISBN-13 giả hợp lệ (đúng chữ số kiểm tra): 978-604-xxxxx-c. */
    static String isbn(int n) {
        String body = "978604" + String.format("%06d", 100000 + n);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = body.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return body + ((10 - sum % 10) % 10);
    }

    private static List<CopyPlan> copies(Random rnd) {
        List<CopyStatus> statuses = new ArrayList<>();
        for (int i = 0; i < LOANED_COPIES; i++) statuses.add(CopyStatus.LOANED);
        for (int i = 0; i < RESERVED_COPIES; i++) statuses.add(CopyStatus.RESERVED);
        for (int i = 0; i < LOST_COPIES; i++) statuses.add(CopyStatus.LOST);
        for (int i = 0; i < DAMAGED_COPIES; i++) statuses.add(CopyStatus.DAMAGED);
        while (statuses.size() < COPIES) statuses.add(CopyStatus.AVAILABLE);
        Collections.shuffle(statuses, rnd);
        List<CopyPlan> list = new ArrayList<>();
        for (int i = 0; i < COPIES; i++) {
            list.add(new CopyPlan(DemoIds.copy(i), i, DemoIds.title(i % TITLES), String.format("DEMO-C%04d", i + 1), statuses.get(i)));
        }
        return list;
    }

    // ---- Mượn trả: phụ trợ ----

    private static LoanItemPlan openItem(List<CopyPlan> copies, int copyIdx) {
        CopyPlan c = copies.get(copyIdx);
        return new LoanItemPlan(c.id(), c.titleId(), null, null);
    }

    private static BorrowingPlan borrowing(LocalDate today, int index, ReaderPlan reader, int borrowDaysAgo,
                                           List<LoanItemPlan> items) {
        LocalDate borrowDate = today.minusDays(borrowDaysAgo);
        return new BorrowingPlan(DemoIds.borrowing(index), index, reader.id(), borrowDaysAgo, DemoPlan.LOAN_DAYS,
                borrowDate, borrowDate.plusDays(DemoPlan.LOAN_DAYS), items);
    }

    /** Chọn ngày (số ngày trước) trong [lo, hi], cuối tuần nặng gấp 3. Luôn rút đúng một số ngẫu nhiên. */
    private static int weightedDay(Random rnd, LocalDate today, int lo, int hi) {
        double total = 0;
        for (int d = lo; d <= hi; d++) total += dayWeight(today.minusDays(d));
        double pick = rnd.nextDouble() * total;
        double acc = 0;
        for (int d = lo; d <= hi; d++) {
            acc += dayWeight(today.minusDays(d));
            if (pick < acc) return d;
        }
        return hi;
    }

    private static double dayWeight(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? 3.0 : 1.0;
    }

    private static int pickFreeCopy(Random copyRnd, List<Integer> availableIdx, Map<Integer, List<int[]>> usage,
                                    int start, int end) {
        int n = availableIdx.size();
        int first = copyRnd.nextInt(n);
        for (int k = 0; k < n; k++) {
            int idx = availableIdx.get((first + k) % n);
            boolean free = true;
            for (int[] other : usage.getOrDefault(idx, List.of())) {
                if (start >= other[1] && other[0] >= end) {
                    free = false;
                    break;
                }
            }
            if (free) return idx;
        }
        return availableIdx.get(first);
    }

    // ---- Bán hàng: phụ trợ ----

    /** {onHand, reserved, ngưỡng riêng (-1 = dùng ngưỡng chung)}; đúng 5 mặt hàng sắp hết (chỉ số 0..4). */
    private static int[][] stockTargets(Random rnd) {
        int[][] t = new int[STOCK_ITEMS][];
        t[0] = new int[]{0, 0, -1};   // available 0
        t[1] = new int[]{3, 1, -1};   // available 2
        t[2] = new int[]{5, 0, -1};   // available 5: biên, đúng bằng ngưỡng chung
        t[3] = new int[]{12, 2, 10};  // available 10: sắp hết theo ngưỡng riêng 10
        t[4] = new int[]{4, 0, -1};   // available 4
        t[5] = new int[]{3, 0, 1};    // available 3 > ngưỡng riêng 1: không sắp hết dù <= ngưỡng chung
        t[6] = new int[]{6, 0, -1};   // available 6 > ngưỡng chung: không sắp hết
        for (int i = 7; i < STOCK_ITEMS; i++) {
            int onHand = 20 + rnd.nextInt(80);
            int reserved = rnd.nextInt(4);
            t[i] = new int[]{onHand, reserved, -1};
        }
        return t;
    }

    private static void buildOrder(Random rnd, int i, List<TitlePlan> titles, long[] prices, int[] sold, int[] returned,
                                   List<OrderPlan> orders, List<SalesReturnPlan> returns, List<RefundPlan> refunds) {
        boolean online = rnd.nextBoolean();
        int daysAgo = rnd.nextInt(DemoPlanGenerator.ORDER_DAYS);
        int minute = 480 + rnd.nextInt(720);
        int lineCount = 1 + rnd.nextInt(3);
        int[] titleIdx = new int[3];
        int[] qty = new int[3];
        for (int k = 0; k < 3; k++) {
            titleIdx[k] = rnd.nextInt(STOCK_ITEMS);
            qty[k] = 1 + rnd.nextInt(3);
        }
        boolean discounted = rnd.nextDouble() < 0.2;
        boolean shipping = rnd.nextBoolean();

        Channel channel = online ? Channel.ONLINE : Channel.POS;
        OrderStatus status = OrderStatus.COMPLETED;
        PaymentStatus payment = PaymentStatus.PAID;
        boolean paid = true;

        if (i < 2) {
            channel = Channel.POS;
            daysAgo = 0;
            if (i == 0) {
                titleIdx[0] = 0;   // mặt hàng hết tồn: bảo đảm sổ cái nhập > 0
                qty[0] = 1;
            }
        } else if (i <= 4) {
            channel = i == 3 ? Channel.ONLINE : Channel.POS;
            daysAgo = 3 + daysAgo % 18;
            payment = i == 3 ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_REFUNDED;
            qty[0] = qty[0] + 1;       // dòng đầu có số lượng >= 2 để trả một phần
            lineCount = Math.max(lineCount, 1);
        } else if (i == 5) {
            channel = Channel.ONLINE;
            status = OrderStatus.CANCELLED;
            payment = PaymentStatus.REFUNDED;
            daysAgo = Math.max(daysAgo, 2);
        } else if (i <= 7) {
            channel = Channel.ONLINE;
            status = OrderStatus.CANCELLED;
            payment = PaymentStatus.UNPAID;
            paid = false;
        } else if (i <= 11) {
            channel = Channel.ONLINE;
            status = OrderStatus.PENDING_CONFIRMATION;
            payment = PaymentStatus.UNPAID;
            paid = false;
            daysAgo = daysAgo % 3;
        } else if (i == 12) {
            channel = Channel.ONLINE;
            status = OrderStatus.SHIPPING;
        } else if (i == 13) {
            channel = Channel.ONLINE;
            status = OrderStatus.CONFIRMED;
            payment = PaymentStatus.UNPAID;
            paid = false;
        } else if (i == 14) {
            channel = Channel.ONLINE;
            status = OrderStatus.READY_FOR_PICKUP;
        } else if (channel == Channel.ONLINE && shipping) {
            status = OrderStatus.SHIPPING;
        }

        // dòng đơn: tên đầu sách khác nhau trong một đơn
        List<OrderLinePlan> lines = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        long subtotal = 0;
        for (int k = 0; k < lineCount; k++) {
            int t = titleIdx[k];
            while (used.contains(t)) t = (t + 1) % STOCK_ITEMS;
            used.add(t);
            OrderLinePlan line = new OrderLinePlan(DemoIds.title(t), titles.get(t).name(), qty[k], prices[t]);
            lines.add(line);
            subtotal += line.lineTotal();
            if (status != OrderStatus.CANCELLED) sold[t] += qty[k];
        }
        long discount = discounted ? (subtotal / 20) / 1000 * 1000 : 0;
        long vat = (subtotal - discount) / 10;
        long shippingFee = channel == Channel.ONLINE ? 30_000L : 0L;
        long total = subtotal - discount + vat + shippingFee;
        Integer paidDaysAgo = paid ? daysAgo : null;
        orders.add(new OrderPlan(DemoIds.order(i), i, DemoPlan.ORDER_PREFIX + String.format("%04d", i + 1), channel,
                status, payment, daysAgo, minute, paidDaysAgo, subtotal, discount, vat, shippingFee, total, lines));

        if (i >= 2 && i <= 4) {
            OrderLinePlan first = lines.get(0);
            int returnDaysAgo = rnd.nextInt(daysAgo + 1);
            int titleIndex = titles.stream().filter(x -> x.id().equals(first.titleId())).findFirst().orElseThrow().index();
            returned[titleIndex] += 1;
            int returnIndex = returns.size();
            returns.add(new SalesReturnPlan(DemoIds.salesReturn(returnIndex), returnIndex, i, first.titleId(), 1, returnDaysAgo));
            boolean pending = i == 3;
            refunds.add(new RefundPlan(DemoIds.refund(refunds.size()), refunds.size(), i, returnIndex, first.unitPrice(),
                    pending ? RefundStatus.PENDING : RefundStatus.REFUNDED, RefundSource.RETURN,
                    pending ? null : returnDaysAgo));
        } else if (i == 5) {
            refunds.add(new RefundPlan(DemoIds.refund(refunds.size()), refunds.size(), i, null, total,
                    RefundStatus.REFUNDED, RefundSource.CANCELLATION, daysAgo));
        }
    }
}
