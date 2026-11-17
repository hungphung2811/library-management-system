package lms.sales.stock.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/** Phiếu nhập kho: chỉ DRAFT sửa/hủy được; đã CONFIRMED thì bất biến (sai thì điều chỉnh kho). */
public final class GoodsReceipt {

    private final UUID id;
    private final UUID tenantId;
    private final String receiptNo;
    private final UUID supplierId;
    private final List<GoodsReceiptLine> lines;
    private final UUID clientRequestId;
    private final UUID createdBy;
    private final Instant createdAt;
    private ReceiptStatus status;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private final long version;

    private GoodsReceipt(UUID id, UUID tenantId, String receiptNo, UUID supplierId, List<GoodsReceiptLine> lines,
                         UUID clientRequestId, UUID createdBy, Instant createdAt, ReceiptStatus status,
                         Instant confirmedAt, UUID confirmedBy, long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.receiptNo = receiptNo;
        this.supplierId = supplierId;
        this.lines = List.copyOf(lines);
        this.clientRequestId = clientRequestId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.version = version;
    }

    public static GoodsReceipt draft(UUID tenantId, String receiptNo, UUID supplierId,
                                     List<GoodsReceiptLine> lines, UUID clientRequestId, UUID createdBy,
                                     Instant now) {
        if (lines == null || lines.isEmpty()) {
            throw SalesException.invalidRequest("Phiếu nhập cần ít nhất một dòng");
        }
        Set<UUID> seen = new HashSet<>();
        for (GoodsReceiptLine l : lines) {
            if (!seen.add(l.titleId())) {
                throw SalesException.invalidRequest("Đầu sách bị lặp trong phiếu nhập");
            }
        }
        return new GoodsReceipt(UUID.randomUUID(), tenantId, receiptNo, supplierId, new ArrayList<>(lines),
                clientRequestId, createdBy, now, ReceiptStatus.DRAFT, null, null, 0);
    }

    public static GoodsReceipt restore(UUID id, UUID tenantId, String receiptNo, UUID supplierId,
                                       List<GoodsReceiptLine> lines, UUID clientRequestId, UUID createdBy,
                                       Instant createdAt, ReceiptStatus status, Instant confirmedAt,
                                       UUID confirmedBy, long version) {
        return new GoodsReceipt(id, tenantId, receiptNo, supplierId, lines, clientRequestId, createdBy, createdAt,
                status, confirmedAt, confirmedBy, version);
    }

    public void confirm(UUID actor, Instant now) {
        requireDraft();
        this.status = ReceiptStatus.CONFIRMED;
        this.confirmedAt = now;
        this.confirmedBy = actor;
    }

    public void cancel() {
        requireDraft();
        this.status = ReceiptStatus.CANCELLED;
    }

    private void requireDraft() {
        if (status != ReceiptStatus.DRAFT) {
            throw new SalesException(ErrorCode.RECEIPT_STATE_CONFLICT, "Phiếu nhập không ở trạng thái nháp");
        }
    }

    public Money totalCost() {
        Money sum = Money.ZERO;
        for (GoodsReceiptLine l : lines) {
            sum = sum.plus(l.unitCost().times(l.quantity()));
        }
        return sum;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String receiptNo() { return receiptNo; }
    public UUID supplierId() { return supplierId; }
    public List<GoodsReceiptLine> lines() { return lines; }
    public UUID clientRequestId() { return clientRequestId; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public ReceiptStatus status() { return status; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public long version() { return version; }
}
