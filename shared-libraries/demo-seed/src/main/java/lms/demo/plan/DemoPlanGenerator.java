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
import static lms.demo.plan.DemoPlan.ShelfPlan;
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
    /** Họ tên độc giả demo; chỉ số chẵn là nam, lẻ là nữ (identity seed dựa vào quy ước này để đặt giới tính). */
    private static final String[] READER_NAMES = {"Nguyễn Văn Minh", "Trần Thị Thu Hà", "Lê Hoàng Long",
            "Phạm Ngọc Anh", "Hoàng Đức Thịnh", "Vũ Thị Lan Hương", "Đặng Quốc Bảo", "Bùi Thanh Trúc",
            "Đỗ Gia Huy", "Ngô Phương Thảo", "Dương Tuấn Kiệt", "Lý Thị Mai Anh", "Phan Công Danh",
            "Trịnh Khánh Linh", "Võ Minh Quân", "Mai Hồng Nhung", "Đinh Xuân Trường", "Lương Thu Hiền",
            "Huỳnh Bảo Long", "Cao Thị Ngọc Diệp", "Tạ Hữu Phước", "Châu Mỹ Duyên", "Lâm Nhật Nam", "Hà Thu Trang"};

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
            // Chi tiết quản lý: tất định theo chỉ số, KHÔNG rút từ rnd (không làm lệch các phần kế hoạch khác)
            long cost = costPrice(prices[i], i);
            stockItems.add(new StockItemPlan(DemoIds.title(i), i, onHand, reserved, threshold, prices[i], received,
                    sold[i], returned[i], receiptIndex,
                    String.format("LMS-%d-%04d", titles.get(i).publishYear(), i + 1),
                    STOCK_SUPPLIERS[i % STOCK_SUPPLIERS.length], cost, binLocation(i), 10 * (1 + i % 4),
                    stockNote(i)));
            receiptLines.get(receiptIndex).add(new ReceiptLinePlan(DemoIds.title(i), received, cost));
        }
        List<ReceiptPlan> receipts = List.of(
                new ReceiptPlan(DemoIds.receipt(0), 0, 0, 50, receiptLines.get(0)),
                new ReceiptPlan(DemoIds.receipt(1), 1, 1, 45, receiptLines.get(1)));

        List<ShelfPlan> shelves = shelves();
        List<CopyPlan> finalCopies = withAcquisition(copies, prices, seed);
        return new DemoPlan(today, seed, staff, readers, titles, finalCopies, borrowings, reservations, fines, suppliers,
                receipts, stockItems, orders, returns, refunds, DemoReferenceData.authors(),
                DemoReferenceData.publishers(), shelves);
    }

    // ---- Kệ và thông tin nhập kho của bản sao (tất định, KHÔNG rút từ rnd chính) ----

    private static final String[] SHELF_ABBR = {"LT", "LS", "VH", "KT", "KH", "TN", "TL", "NT"};

    /** 16 kệ: mỗi chủ đề 2 kệ; 4 chủ đề đầu ở Tầng 1, 4 chủ đề sau ở Tầng 2; khu đặt theo tên chủ đề. */
    static List<ShelfPlan> shelves() {
        List<ShelfPlan> list = new ArrayList<>();
        for (int t = 0; t < TOPICS.length; t++) {
            for (int n = 1; n <= 2; n++) {
                int index = list.size();
                list.add(new ShelfPlan(DemoIds.of("shelf", index), index, String.format("%s-%02d", SHELF_ABBR[t], n),
                        t < 4 ? "Tầng 1" : "Tầng 2", "Khu " + TOPICS[t], n == 1 ? 60 : 40));
            }
        }
        return list;
    }

    /** Giá bìa dự phòng của đầu sách không có mặt hàng bán (cùng công thức CatalogDemoSeedStep). */
    private static long coverPriceOf(int titleIndex, long[] prices) {
        return titleIndex < prices.length ? prices[titleIndex] : 90_000L + (titleIndex % 7) * 10_000L;
    }

    /**
     * Mốc cố định cho ngày nhập/kiểm kê của bản sao: KHÔNG theo ngày chạy để kế hoạch bản sao giữ nguyên khi đổi ngày
     * (bất biến của DemoPlan) và để chạy lại không đổi dữ liệu đã seed. Phải nằm trong quá khứ so với ngày seed.
     */
    static final LocalDate ACQUISITION_ANCHOR = LocalDate.of(2026, 11, 1);

    private static List<CopyPlan> withAcquisition(List<CopyPlan> copies, long[] prices, long seed) {
        List<CopyPlan> out = new ArrayList<>(copies.size());
        for (CopyPlan c : copies) {
            Random r = new Random(seed * 31 + 0xA5C0DEL + c.index());
            int titleIndex = c.index() % TITLES;
            int slot = c.index() % 10;
            String source = slot <= 5 ? "PURCHASE" : slot <= 7 ? "DONATION" : slot == 8 ? "EXCHANGE" : "OTHER";
            int acquiredDaysAgo = 120 + r.nextInt(1700);
            LocalDate acquired = ACQUISITION_ANCHOR.minusDays(acquiredDaysAgo);
            LocalDate inventory = ACQUISITION_ANCHOR.minusDays(1 + r.nextInt(90));
            Long price = source.equals("PURCHASE") ? costPrice(coverPriceOf(titleIndex, prices), c.index()) : null;
            int roll = r.nextInt(100);
            String grade = acquiredDaysAgo < 365 ? (roll < 60 ? "NEW" : "GOOD")
                    : roll < 20 ? "NEW" : roll < 65 ? "GOOD" : roll < 88 ? "FAIR" : "WORN";
            String note = null;
            if (c.status() == CopyStatus.DAMAGED) {
                grade = "WORN";
                note = "Gáy sách bong, cần đóng lại";
            } else if (c.status() == CopyStatus.LOST) {
                grade = null;
                note = "Đã báo mất, chờ xử lý theo quy định";
            } else if (grade.equals("FAIR")) {
                note = roll % 2 == 0 ? "Góc bìa mòn nhẹ" : "Có vết ố ở vài trang";
            } else if (grade.equals("WORN")) {
                note = "Bìa trầy xước, ruột còn tốt";
            }
            int shelf = (titleIndex / 8) * 2 + c.index() % 2;
            out.add(new CopyPlan(c.id(), c.index(), c.titleId(), c.barcode(), c.status(), acquired, source, price,
                    grade, note, inventory, shelf));
        }
        return out;
    }

    // ---- Chi tiết quản lý mặt hàng (tất định theo chỉ số) ----

    /** Nhà cung cấp/nhà phát hành thật, xoay vòng theo chỉ số mặt hàng. */
    static final String[] STOCK_SUPPLIERS = {"Fahasa", "Nhà sách Phương Nam", "Nhã Nam", "First News - Trí Việt",
            "Alpha Books", "NXB Trẻ", "Thái Hà Books", "NXB Kim Đồng"};

    /** Giá nhập = 55%..70% giá bán (bước 1%), làm tròn 500 đồng; luôn nhỏ hơn giá bán. */
    static long costPrice(long price, int index) {
        int percent = 55 + (index * 7) % 16;
        long cost = Math.round(price * percent / 100.0 / 500.0) * 500L;
        return Math.min(cost, price - 500L);
    }

    /** Vị trí kho "K{kệ}-{hàng}{ô}": mỗi kệ 16 mặt hàng, hàng A..D, ô 1..4; duy nhất theo chỉ số (đến 64). */
    static String binLocation(int index) {
        return "K" + (index / 16 + 1) + "-" + (char) ('A' + (index % 16) / 4) + (1 + index % 4);
    }

    private static String stockNote(int index) {
        if (index % 9 == 0) return "Bán chạy, ưu tiên đặt lại khi tồn thấp.";
        if (index % 13 == 4) return "Bìa dễ trầy, kiểm tra kỹ khi nhận hàng.";
        if (index % 11 == 6) return "Hàng đặt trước từ nhà phát hành, giao theo đợt.";
        return null;
    }

    // ---- Ghi chú đơn hàng (tất định theo chỉ số và trạng thái) ----

    private static final String[] ONLINE_CUSTOMER_NOTES = {
            "Giao giờ hành chính, gọi trước khi đến giúp mình.",
            "Gói quà giúp mình, kèm thiệp chúc mừng sinh nhật.",
            "Nếu mình không có nhà thì gửi bảo vệ tòa nhà nhé.",
            "Nếu hết bản này, cho mình xin bản tái bản mới nhất.",
            "Cần xuất hóa đơn công ty, thông tin mình gửi qua email.",
            "Giao sau 18h các ngày trong tuần."};

    private static String customerNote(int index, Channel channel) {
        if (channel == Channel.ONLINE) {
            return index % 5 == 2 ? null : ONLINE_CUSTOMER_NOTES[index % ONLINE_CUSTOMER_NOTES.length];
        }
        return index % 7 == 3 ? "Khách xin bọc bìa sách giúp." : null;
    }

    private static String staffNote(int index, Channel channel, OrderStatus status, PaymentStatus payment,
                                    long discount) {
        if (payment == PaymentStatus.PARTIALLY_REFUNDED) {
            return "Khách trả 1 cuốn lỗi in, đã lập phiếu trả và hoàn tiền phần tương ứng.";
        }
        switch (status) {
            case PENDING_CONFIRMATION:
                return index % 2 == 0 ? "Chờ xác nhận tồn kho với thủ kho."
                        : "Khách mới, gọi xác nhận địa chỉ trước khi giao.";
            case CONFIRMED:
                return "Đã gọi khách xác nhận, chờ chuyển khoản.";
            case SHIPPING:
                return "Đã bàn giao đơn vị vận chuyển, mã vận đơn ghi trên phiếu giao.";
            case READY_FOR_PICKUP:
                return "Đã đóng gói, để ở quầy số 2, giữ trong 3 ngày.";
            case CANCELLED:
                return payment == PaymentStatus.REFUNDED ? "Khách đổi ý, đã hoàn tiền và nhập lại kho."
                        : "Khách không nghe máy 2 lần, hủy theo quy định.";
            default:
                break;
        }
        if (discount > 0) {
            return "Giảm 5% theo chương trình khuyến mãi, quản lý đã duyệt.";
        }
        if (channel == Channel.ONLINE && index % 4 == 0) {
            return "Khách quen, ưu tiên giao nhanh các lần sau.";
        }
        return null;
    }

    /** Số ngày từ ngày tạo đến ngày giao/nhận dự kiến: chỉ đơn online không bị hủy. */
    private static Integer expectedDeliveryDays(int index, Channel channel, OrderStatus status) {
        if (channel != Channel.ONLINE || status == OrderStatus.CANCELLED) {
            return null;
        }
        return status == OrderStatus.READY_FOR_PICKUP ? 1 : 2 + index % 3;
    }

    // ---- Con người, danh mục ----

    private static List<StaffPlan> staff() {
        return List.of(
                new StaffPlan(DemoIds.staff(0), 0, StaffRole.TENANT_ADMIN, "demo.admin", "Nguyễn Quốc Hưng", "demo.admin@" + DemoPlan.EMAIL_DOMAIN),
                new StaffPlan(DemoIds.staff(1), 1, StaffRole.LIBRARIAN, "demo.thuthu", "Lê Thị Hồng Nhung", "demo.thuthu@" + DemoPlan.EMAIL_DOMAIN),
                new StaffPlan(DemoIds.staff(2), 2, StaffRole.STORE_MANAGER, "demo.cuahang", "Trần Minh Tuấn", "demo.cuahang@" + DemoPlan.EMAIL_DOMAIN));
    }

    private static List<ReaderPlan> readers() {
        List<ReaderPlan> list = new ArrayList<>();
        for (int i = 0; i < READERS; i++) {
            String name = READER_NAMES[i % READER_NAMES.length];
            String username = String.format("demo.docgia%02d", i + 1);
            list.add(new ReaderPlan(DemoIds.reader(i), i, username, name, username + "@" + DemoPlan.EMAIL_DOMAIN,
                    String.format("DEMO-R%03d", i + 1)));
        }
        return list;
    }

    private static List<TitlePlan> titles(Random rnd) {
        List<DemoTitleData.Row> rows = DemoTitleData.rows();
        if (rows.size() != TITLES) {
            throw new IllegalStateException("Dữ liệu sách demo phải có đúng " + TITLES + " dòng, hiện có " + rows.size());
        }
        List<TitlePlan> list = new ArrayList<>();
        for (int i = 0; i < TITLES; i++) {
            DemoTitleData.Row r = rows.get(i);
            // Giữ nguyên số lần rút từ rnd như trước (năm ngẫu nhiên cũ) để các phần kế hoạch sinh sau không đổi.
            rnd.nextInt(35);
            // Ảnh bìa giữ chỗ ổn định theo index; cứ 5 sách có 1 sách không có ảnh để demo trường hợp thiếu.
            String cover = i % 5 == 4 ? null : "https://picsum.photos/seed/lms-demo-" + i + "/300/450";
            list.add(new TitlePlan(DemoIds.title(i), i, r.name(), r.author(), isbn(i), TOPICS[i / FORMS.length],
                    r.year(), r.publisher(), r.subtitle(), r.edition(), r.language(), r.pageCount(), r.format(),
                    r.translator(), r.series(), r.seriesNumber(), cover, r.tags()));
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
                status, payment, daysAgo, minute, paidDaysAgo, subtotal, discount, vat, shippingFee, total, lines,
                customerNote(i, channel), staffNote(i, channel, status, payment, discount),
                expectedDeliveryDays(i, channel, status)));

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
