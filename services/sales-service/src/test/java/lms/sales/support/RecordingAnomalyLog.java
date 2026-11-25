package lms.sales.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import lms.sales.ordering.application.PaymentAnomaly;
import lms.sales.ordering.application.PaymentAnomalyLogPort;
import lms.sales.ordering.application.PaymentAnomalySource;
import lms.sales.ordering.application.PaymentAnomalyType;

/** Cổng log bất thường giả: ghi lại các lời gọi để test khẳng định (không in ra đâu cả). */
public class RecordingAnomalyLog implements PaymentAnomalyLogPort {

    public final List<PaymentAnomaly> anomalies = new CopyOnWriteArrayList<>();
    public final List<String> recovered = new CopyOnWriteArrayList<>();
    public final List<String> conflicts = new CopyOnWriteArrayList<>();
    public RuntimeException failWith;

    @Override
    public void log(PaymentAnomaly anomaly) {
        if (failWith != null) {
            throw failWith;
        }
        anomalies.add(anomaly);
    }

    @Override
    public void recovered(String provider, int failures) {
        recovered.add(provider + ":" + failures);
    }

    @Override
    public void conflict(PaymentAnomalySource source, String provider, UUID tenantId, UUID intentId, String reference) {
        conflicts.add(source + ":" + provider);
    }

    public List<PaymentAnomalyType> types() {
        return new ArrayList<>(anomalies.stream().map(PaymentAnomaly::type).toList());
    }
}
