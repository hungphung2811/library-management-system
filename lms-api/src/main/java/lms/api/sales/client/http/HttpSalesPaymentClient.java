package lms.api.sales.client.http;

import java.util.UUID;
import lms.api.sales.client.SalesPaymentClient;
import lms.api.sales.dto.SalesDtos.ConfirmPaymentRequest;
import lms.api.sales.dto.SalesDtos.PaymentIntentView;
import org.springframework.web.client.RestClient;

/** Gọi /internal/payments/intents/** của sales-service. Token, tenant và correlation id do RestClient chuyển tiếp. */
public class HttpSalesPaymentClient implements SalesPaymentClient {

    private static final String BASE = "/internal/payments/intents";

    private final RestClient client;

    public HttpSalesPaymentClient(RestClient client) {
        this.client = client;
    }

    @Override
    public PaymentIntentView getIntent(UUID id) {
        return client.get().uri(BASE + "/{id}", id).retrieve().body(PaymentIntentView.class);
    }

    @Override
    public PaymentIntentView cancelIntent(UUID id) {
        return client.post().uri(BASE + "/{id}/cancel", id).retrieve().body(PaymentIntentView.class);
    }

    @Override
    public PaymentIntentView confirmIntent(UUID id, ConfirmPaymentRequest request) {
        var spec = client.post().uri(BASE + "/{id}/confirm", id);
        if (request != null) {
            spec = spec.body(request);
        }
        return spec.retrieve().body(PaymentIntentView.class);
    }

    @Override
    public PaymentIntentView simulatePaid(UUID id) {
        return client.post().uri(BASE + "/{id}/simulate-paid", id).retrieve().body(PaymentIntentView.class);
    }
}
