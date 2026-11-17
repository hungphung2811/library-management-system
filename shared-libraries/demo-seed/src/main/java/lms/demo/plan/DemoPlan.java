package lms.demo.plan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kế hoạch dữ liệu demo dùng chung cho identity, catalog, circulation, sales (ADR-0018 mục 2.4). Java thuần.
 * <p>
 * Tất định: cùng {@code (today, seed)} luôn ra cùng kế hoạch. Id, người mượn, bản sao đang cho mượn, trạng thái bản sao,
 * số lượng và các bất biến không phụ thuộc {@code today}; chỉ ngày tuyệt đối (và độ lệch ngày của lịch sử, do trọng số
 * cuối tuần) dịch theo {@code today}. Các độ lệch dùng tên {@code ...DaysAgo}.
 * <p>
 * Bất biến: đúng {@value #OVERDUE_BORROWINGS} phiếu quá hạn, đúng {@value #UNPAID_FINES} phạt chưa thu
 * (tổng {@value #UNPAID_FINES_TOTAL} đồng), đúng {@value #LOW_STOCK_ITEMS} mặt hàng sắp hết, từ 60 đầu sách,
 * từ 150 bản sao đủ 5 trạng thái, từ 120 phiếu mượn trong 60 ngày, từ 40 đơn trong 30 ngày.
 */
public record DemoPlan(
        LocalDate today,
        long seed,
        List<StaffPlan> staff,
        List<ReaderPlan> readers,
        List<TitlePlan> titles,
        List<CopyPlan> copies,
        List<BorrowingPlan> borrowings,
        List<ReservationPlan> reservations,
        List<FinePlan> fines,
        List<SupplierPlan> suppliers,
        List<ReceiptPlan> receipts,
        List<StockItemPlan> stockItems,
        List<OrderPlan> orders,
        List<SalesReturnPlan> salesReturns,
        List<RefundPlan> refunds) {

    public static final long DEFAULT_SEED = 20261117L;

    public static final String TENANT_CODE = "demo";
    public static final String TENANT_NAME = "Thư viện Demo";
    public static final String ORDER_PREFIX = "DEMO-";
    public static final String EMAIL_DOMAIN = "demo.local";

    public static final int OVERDUE_BORROWINGS = 6;
    public static final int UNPAID_FINES = 4;
    public static final long UNPAID_FINES_TOTAL = 80_000L;
    public static final int LOW_STOCK_ITEMS = 5;
    /** Ngưỡng tồn thấp chung của tenant demo (sales/settings). */
    public static final int GENERAL_LOW_STOCK_THRESHOLD = 5;
    public static final int LOAN_DAYS = 14;
    public static final long FINE_PER_DAY = 2_000L;

    public DemoPlan {
        staff = List.copyOf(staff);
        readers = List.copyOf(readers);
        titles = List.copyOf(titles);
        copies = List.copyOf(copies);
        borrowings = List.copyOf(borrowings);
        reservations = List.copyOf(reservations);
        fines = List.copyOf(fines);
        suppliers = List.copyOf(suppliers);
        receipts = List.copyOf(receipts);
        stockItems = List.copyOf(stockItems);
        orders = List.copyOf(orders);
        salesReturns = List.copyOf(salesReturns);
        refunds = List.copyOf(refunds);
    }

    public static DemoPlan generate(LocalDate today, long seed) {
        return DemoPlanGenerator.generate(today, seed);
    }

    public static DemoPlan generate(LocalDate today) {
        return generate(today, DEFAULT_SEED);
    }

    // ---- Truy vấn bất biến ----

    /** Phiếu còn ít nhất một bản sao chưa trả và hạn trả trước hôm nay. */
    public List<BorrowingPlan> overdueBorrowings() {
        return borrowings.stream().filter(b -> b.isOverdue(today)).toList();
    }

    public List<FinePlan> unpaidFines() {
        return fines.stream().filter(f -> f.status() == FineStatus.UNPAID).toList();
    }

    public long unpaidFinesTotal() {
        return unpaidFines().stream().mapToLong(FinePlan::amount).sum();
    }

    /** Mặt hàng sắp hết theo định nghĩa của sales: available <= ngưỡng riêng, nếu không có thì ngưỡng chung. */
    public List<StockItemPlan> lowStockItems() {
        return stockItems.stream().filter(s -> s.isLowStock(GENERAL_LOW_STOCK_THRESHOLD)).toList();
    }

    // ---- Kiểu dữ liệu ----

    public enum StaffRole { TENANT_ADMIN, LIBRARIAN, STORE_MANAGER }

    public enum CopyStatus { AVAILABLE, LOANED, RESERVED, LOST, DAMAGED }

    public enum FineStatus { UNPAID, PAID }

    public enum Channel { POS, ONLINE }

    public enum OrderStatus { PENDING_CONFIRMATION, CONFIRMED, READY_FOR_PICKUP, SHIPPING, COMPLETED, CANCELLED }

    public enum PaymentStatus { UNPAID, PAID, PARTIALLY_REFUNDED, REFUNDED }

    public enum RefundStatus { PENDING, REFUNDED }

    public enum RefundSource { RETURN, CANCELLATION }

    public record StaffPlan(UUID id, int index, StaffRole role, String username, String fullName, String email) {
    }

    public record ReaderPlan(UUID id, int index, String username, String fullName, String email, String cardNo) {
    }

    public record TitlePlan(UUID id, int index, String name, String author, String isbn, String category, int publishYear) {
    }

    public record CopyPlan(UUID id, int index, UUID titleId, String barcode, CopyStatus status) {
    }

    /** {@code returnedDaysAgo == null}: chưa trả. */
    public record LoanItemPlan(UUID copyId, UUID titleId, Integer returnedDaysAgo, LocalDate returnDate) {
        public boolean isReturned() {
            return returnedDaysAgo != null;
        }
    }

    public record BorrowingPlan(UUID id, int index, UUID readerId, int borrowDaysAgo, int loanDays,
                                LocalDate borrowDate, LocalDate dueDate, List<LoanItemPlan> items) {
        public BorrowingPlan {
            items = List.copyOf(items);
        }

        /** Phiếu ACTIVE: còn ít nhất một bản sao chưa trả. */
        public boolean isActive() {
            return items.stream().anyMatch(i -> !i.isReturned());
        }

        /** Quá hạn: ACTIVE và hạn trả trước hôm nay (đến hạn đúng hôm nay chưa quá hạn). */
        public boolean isOverdue(LocalDate today) {
            return isActive() && dueDate.isBefore(today);
        }
    }

    /** Đặt trước đang giữ một bản sao {@code RESERVED}, còn hạn giữ chỗ. */
    public record ReservationPlan(UUID id, int index, UUID readerId, UUID titleId, UUID copyId,
                                  int createdDaysAgo, int holdDays) {
    }

    public record FinePlan(UUID id, int index, UUID borrowingId, UUID readerId, long amount, FineStatus status,
                           int lateDays, int createdDaysAgo, Integer paidDaysAgo) {
    }

    public record SupplierPlan(UUID id, int index, String name, String contactName, String email) {
    }

    public record ReceiptLinePlan(UUID titleId, int quantity, long unitCost) {
    }

    /** Phiếu nhập đã xác nhận. */
    public record ReceiptPlan(UUID id, int index, int supplierIndex, int confirmedDaysAgo, List<ReceiptLinePlan> lines) {
        public ReceiptPlan {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Mặt hàng tồn kho. Sổ cái: {@code +receivedQuantity (nhập) - soldQuantity (bán) + returnedQuantity (khách trả)}
     * và tổng phải bằng {@code onHand}.
     */
    public record StockItemPlan(UUID titleId, int index, int onHand, int reserved, Integer lowStockThreshold,
                                long unitPrice, int receivedQuantity, int soldQuantity, int returnedQuantity,
                                int receiptIndex) {
        public int available() {
            return onHand - reserved;
        }

        public boolean isLowStock(int generalThreshold) {
            int threshold = lowStockThreshold != null ? lowStockThreshold : generalThreshold;
            return available() <= threshold;
        }

        public int ledgerTotal() {
            return receivedQuantity - soldQuantity + returnedQuantity;
        }
    }

    public record OrderLinePlan(UUID titleId, String titleName, int quantity, long unitPrice) {
        public long lineTotal() {
            return quantity * unitPrice;
        }
    }

    /**
     * Đơn hàng. {@code orderNo} có tiền tố {@code DEMO-}. {@code paidDaysAgo == null}: chưa thanh toán.
     * {@code minuteOfDay} (0..1439, giờ VN) là giờ trong ngày; người ghi phải kẹp không vượt quá hiện tại với đơn hôm nay.
     */
    public record OrderPlan(UUID id, int index, String orderNo, Channel channel, OrderStatus status,
                            PaymentStatus paymentStatus, int createdDaysAgo, int minuteOfDay, Integer paidDaysAgo,
                            long subtotal, long discount, long vat, long shippingFee, long total,
                            List<OrderLinePlan> lines) {
        public OrderPlan {
            lines = List.copyOf(lines);
        }

        public boolean isPaid() {
            return paidDaysAgo != null;
        }
    }

    public record SalesReturnPlan(UUID id, int index, int orderIndex, UUID titleId, int quantity, int createdDaysAgo) {
    }

    /** {@code returnIndex == null} khi hoàn do hủy đơn. {@code refundedDaysAgo == null} khi PENDING. */
    public record RefundPlan(UUID id, int index, int orderIndex, Integer returnIndex, long amount,
                             RefundStatus status, RefundSource source, Integer refundedDaysAgo) {
    }
}
