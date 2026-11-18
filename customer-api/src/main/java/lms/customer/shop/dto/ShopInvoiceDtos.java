package lms.customer.shop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lms.customer.web.NoControlChars;

/**
 * DTO công khai của yêu cầu xuất hóa đơn (ADR-0030 gói A, D2). Request là record ALLOWLIST (không chuyển thân thô); thông
 * tin người mua là dữ liệu cá nhân nên {@code toString} không in. Phản hồi chỉ có trạng thái yêu cầu, không lặp lại thông
 * tin khách đã gửi. Hóa đơn nội bộ chưa có giá trị pháp lý của hóa đơn điện tử.
 */
public final class ShopInvoiceDtos {

    private ShopInvoiceDtos() {
    }

    /** Có {@code taxCode} thì bắt buộc {@code company} và {@code address} (sales kiểm); MST sai định dạng thì 400 INVALID_TAX_CODE. */
    public record InvoiceBuyerRequest(
            @Size(max = 255) @NoControlChars String name,
            @Size(max = 255) @NoControlChars String company,
            @Size(max = 14) @NoControlChars String taxCode,
            @Size(max = 500) @NoControlChars String address,
            @Size(max = 255) @NoControlChars String email) {

        @Override
        public String toString() {
            return "InvoiceBuyerRequest[***]";
        }
    }

    public record InvoiceRequestBody(@NotNull @Valid InvoiceBuyerRequest buyer) {

        @Override
        public String toString() {
            return "InvoiceRequestBody[***]";
        }
    }

    /** Trạng thái yêu cầu: OPEN (chờ cửa hàng xuất) hoặc FULFILLED (đã có hóa đơn). */
    public record InvoiceRequestStatusView(UUID orderId, String status) {
    }
}
