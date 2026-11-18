package lms.sales.ordering.application;

/** Kết quả tạo đơn POS: đơn, kèm lệnh thanh toán khi đơn thanh toán QR (null với các phương thức khác). */
public record PosOrderResult(OrderResponse order, PaymentIntentResponse paymentIntent) {
}
