package lms.customer.shop.client;

import java.util.UUID;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestBody;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestStatusView;

/**
 * Cổng sang sales {@code PUT /internal/sales/shop/orders/{id}/invoice-request} (ShopInvoiceController). Chuyển tiếp TOKEN
 * CỦA KHÁCH: sales kiểm module, khóa {@code ShopInvoiceController/saveRequest} và sở hữu đơn theo userId trong token.
 */
public interface ShopInvoiceClient {

    InvoiceRequestStatusView saveInvoiceRequest(String userToken, UUID orderId, InvoiceRequestBody request);
}
