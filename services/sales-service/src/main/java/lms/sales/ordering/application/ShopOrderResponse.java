package lms.sales.ordering.application;

import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;

/** Đơn của chính khách: người nhận hiển thị đầy đủ (dữ liệu của khách), phần còn lại như {@link OrderResponse}. */
public record ShopOrderResponse(OrderResponse order, RecipientView recipient) {

    public record RecipientView(String name, String phone, String address) {
        @Override
        public String toString() {
            return "RecipientView[***]";
        }
    }

    public static ShopOrderResponse from(SalesOrder o) {
        Recipient r = o.recipient();
        return new ShopOrderResponse(OrderResponse.from(o),
                r == null ? null : new RecipientView(r.name(), r.phone(), r.address()));
    }
}
