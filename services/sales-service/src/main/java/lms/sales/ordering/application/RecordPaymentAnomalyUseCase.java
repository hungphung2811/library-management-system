package lms.sales.ordering.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ghi nhận một bất thường thanh toán (ADR-0029 D4): luôn ghi log máy; chỉ khi loại có audit, biết tenant và biết lệnh
 * thì thêm audit {@code PAYMENT_ANOMALY} qua {@link PaymentAnomalyAuditUseCase} (transaction riêng). KHÔNG BAO GIỜ ném
 * exception ra ngoài: lỗi của chính việc ghi chỉ được log tên lớp lỗi, để không che exception gốc hay làm hỏng phản hồi.
 * Không transactional.
 */
public class RecordPaymentAnomalyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordPaymentAnomalyUseCase.class);

    private final PaymentAnomalyLogPort logPort;
    private final PaymentAnomalyAuditUseCase auditUseCase;

    public RecordPaymentAnomalyUseCase(PaymentAnomalyLogPort logPort, PaymentAnomalyAuditUseCase auditUseCase) {
        this.logPort = logPort;
        this.auditUseCase = auditUseCase;
    }

    public void report(PaymentAnomaly anomaly) {
        try {
            logPort.log(anomaly);
        } catch (RuntimeException e) {
            log.warn("Không ghi được log bất thường thanh toán type={} errorClass={}", anomaly.type(),
                    e.getClass().getSimpleName());
        }
        if (anomaly.type().audited() && anomaly.tenantId() != null && anomaly.intentId() != null) {
            try {
                auditUseCase.recordOnce(anomaly);
            } catch (RuntimeException e) {
                log.warn("Không ghi được audit bất thường thanh toán type={} errorClass={}", anomaly.type(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
