package lms.api.reports.client.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lms.api.reports.client.ReportClient;
import lms.api.reports.client.ReportSink;
import lms.api.reports.dto.OrdersReportQuery;
import lms.api.reports.dto.ReportHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Chuyển luồng CSV bằng {@code exchange}: 2xx thì đưa luồng thẳng cho sink, 4xx/5xx đọc thân lỗi (nhỏ) rồi ném
 * ngoại lệ để ApiExceptionHandler xử lý như mọi lời gọi khác.
 */
public class HttpReportClient implements ReportClient {

    static final String OVERDUE = "/internal/reporting/lending/overdue-borrowings.csv";
    static final String STOCK = "/internal/sales/reporting/stock.csv";
    static final String ORDERS = "/internal/sales/reporting/orders.csv";
    static final String TRUNCATED_HEADER = "X-Report-Truncated";

    private final RestClient circulation;
    private final RestClient sales;

    public HttpReportClient(RestClient circulation, RestClient sales) {
        this.circulation = circulation;
        this.sales = sales;
    }

    @Override
    public void overdueBorrowings(ReportSink sink) {
        stream(circulation, OVERDUE, new LinkedHashMap<>(), sink);
    }

    @Override
    public void stock(String lowStockOnly, ReportSink sink) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("lowStockOnly", lowStockOnly);
        stream(sales, STOCK, params, sink);
    }

    @Override
    public void orders(OrdersReportQuery q, ReportSink sink) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("from", q.from());
        params.put("to", q.to());
        params.put("channel", q.channel());
        params.put("status", q.status());
        stream(sales, ORDERS, params, sink);
    }

    private static void stream(RestClient client, String path, Map<String, String> params, ReportSink sink) {
        client.get().uri(b -> {
            b.path(path);
            params.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    b.queryParam(k, v);
                }
            });
            return b.build();
        }).accept(MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_JSON).exchange((request, response) -> {
            if (response.getStatusCode().is2xxSuccessful()) {
                HttpHeaders h = response.getHeaders();
                sink.accept(new ReportHeaders(h.getFirst(HttpHeaders.CONTENT_TYPE),
                        h.getFirst(HttpHeaders.CONTENT_DISPOSITION), h.getFirst(TRUNCATED_HEADER)), response.getBody());
                return null;
            }
            byte[] payload = response.getBody().readAllBytes();
            if (response.getStatusCode().is4xxClientError()) {
                throw HttpClientErrorException.create(response.getStatusCode(), response.getStatusText(),
                        response.getHeaders(), payload, StandardCharsets.UTF_8);
            }
            throw HttpServerErrorException.create(response.getStatusCode(), response.getStatusText(),
                    response.getHeaders(), payload, StandardCharsets.UTF_8);
        });
    }
}
