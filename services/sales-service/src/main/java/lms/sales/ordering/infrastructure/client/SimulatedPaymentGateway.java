package lms.sales.ordering.infrastructure.client;

import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.domain.PaymentIntent;

/**
 * Cổng thanh toán giả lập nội bộ (sandbox, ADR-0020): không gọi dịch vụ ngoài, chỉ sinh chuỗi VietQR hợp lệ về mặt định
 * dạng từ tài khoản/ngân hàng demo trong cấu hình {@code app.payments.sandbox.*}. Tiền "về" khi webhook giả lập
 * được gọi (nút giả lập hoặc công cụ ngoài, có ký HMAC). KHÔNG dùng cho production.
 */
public class SimulatedPaymentGateway implements PaymentGatewayPort {

    private final String bankBin;
    private final String accountNo;
    private final String accountName;

    public SimulatedPaymentGateway(String bankBin, String accountNo, String accountName) {
        this.bankBin = bankBin;
        this.accountNo = accountNo;
        this.accountName = accountName;
    }

    @Override
    public String provider() {
        return PaymentIntent.PROVIDER_SIMULATED;
    }

    @Override
    public QrCharge createQr(ChargeRequest request) {
        return new QrCharge(VietQrPayload.build(bankBin, accountNo, request.amount().amount(), request.reference(),
                accountName));
    }
}
