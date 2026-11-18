package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceRequest;

/** Yêu cầu xuất hóa đơn. Thông tin người mua là dữ liệu cá nhân: {@code toString} không in. */
public record InvoiceRequestView(UUID id, UUID orderId, String buyerName, String buyerCompany, String buyerTaxCode,
                                 String buyerAddress, String buyerEmail, String source, String status,
                                 Instant createdAt, Instant updatedAt, long version) {

    public static InvoiceRequestView from(InvoiceRequest r) {
        var b = r.buyer();
        return new InvoiceRequestView(r.id(), r.orderId(), b.name(), b.company(), b.taxCode(), b.address(),
                b.email(), r.source().name(), r.status().name(), r.createdAt(), r.updatedAt(), r.version());
    }

    @Override
    public String toString() {
        return "InvoiceRequestView[order=" + orderId + ", " + status + "]";
    }
}
