package lms.sales.invoicing.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rounding;
import lms.sales.shared.domain.SalesException;

/**
 * Dựng các dòng hóa đơn điều chỉnh giảm từ các dòng trả hàng (ADR-0030 D1.5). Số tiền dòng điều chỉnh là số hoàn
 * ({@code refundAmount}) của dòng trả; thuế của dòng điều chỉnh phân bổ theo thuế dòng gốc bằng phương pháp LŨY KẾ:
 * {@code vat = round(đã trả + hoàn, thuếGốc/thànhTiềnGốc) - round(đã trả, thuếGốc/thànhTiềnGốc)} (HALF_UP). Với phiếu trả đầu
 * tiên của dòng, đúng bằng {@code round(hoàn x thuếGốc / thànhTiềnGốc)} như ADR; qua nhiều phiếu thì tổng thuế điều chỉnh
 * khi trả hết dòng đúng bằng thuế dòng gốc (không lệch 1 đồng do làm tròn từng phần). Tiền trước thuế là phần còn lại.
 * Bất biến: lũy kế số lượng và số tiền của một dòng không vượt dòng gốc, nên tổng điều chỉnh không vượt hóa đơn gốc.
 */
public final class AdjustmentPlanner {

    private AdjustmentPlanner() {
    }

    /** Dòng trả hàng (số liệu của phiếu trả). */
    public record Returned(UUID orderLineId, int quantity, long refundAmount) {
    }

    /** Đã điều chỉnh trước đó cho một dòng gốc (từ các hóa đơn điều chỉnh còn hiệu lực). */
    public record Prior(int quantity, long amount) {
        public static final Prior NONE = new Prior(0, 0);
    }

    public static List<InvoiceLine> plan(List<InvoiceLine> originalLines, Map<UUID, Prior> prior,
                                         List<Returned> returned, boolean pricesIncludeVat) {
        Map<UUID, InvoiceLine> byOrderLine = new HashMap<>();
        for (InvoiceLine l : originalLines) {
            if (l.orderLineId() != null) {
                byOrderLine.put(l.orderLineId(), l);
            }
        }
        Map<UUID, Prior> running = new HashMap<>(prior);
        List<InvoiceLine> out = new ArrayList<>();
        for (Returned r : returned) {
            if (r.refundAmount() <= 0) {
                continue; // dòng trả không hoàn tiền thì không có gì để điều chỉnh
            }
            InvoiceLine orig = byOrderLine.get(r.orderLineId());
            if (orig == null) {
                throw SalesException.invalidRequest("Dòng trả không thuộc hóa đơn gốc");
            }
            Prior p = running.getOrDefault(r.orderLineId(), Prior.NONE);
            long cumAmount = Math.addExact(p.amount(), r.refundAmount());
            int cumQty = p.quantity() + r.quantity();
            if (r.quantity() <= 0 || cumQty > orig.quantity() || cumAmount > orig.lineTotal().amount()) {
                throw new SalesException(ErrorCode.INVOICE_STATE_CONFLICT,
                        "Điều chỉnh vượt số lượng hoặc giá trị của hóa đơn gốc");
            }
            long vat = proportionalVat(orig, cumAmount) - proportionalVat(orig, p.amount());
            long before = r.refundAmount() - vat;
            Money gross = orig.unitPrice().times(r.quantity());
            long base = pricesIncludeVat ? r.refundAmount() : before;
            long discount = Math.max(0, gross.amount() - base);
            out.add(new InvoiceLine(out.size() + 1, orig.orderLineId(), orig.itemName(), orig.isbn(), orig.unit(),
                    r.quantity(), orig.unitPrice(), Money.of(discount), Money.of(before), orig.vatRateBps(),
                    Money.of(vat), Money.of(r.refundAmount())));
            running.put(r.orderLineId(), new Prior(cumQty, cumAmount));
        }
        if (out.isEmpty()) {
            throw SalesException.invalidRequest("Không có dòng nào để điều chỉnh");
        }
        return List.copyOf(out);
    }

    /** round(lũyKế x thuếGốc / thànhTiềnGốc) HALF_UP; 0 khi dòng gốc không có thuế. */
    static long proportionalVat(InvoiceLine orig, long cumulative) {
        if (cumulative == 0 || orig.vatAmount().isZero()) {
            return 0;
        }
        return Rounding.mulDivHalfUp(cumulative, orig.vatAmount().amount(), orig.lineTotal().amount());
    }
}
