package lms.api.sales.client.http;

import java.util.List;
import lms.api.sales.client.SalesLogClient;
import lms.api.sales.dto.SalesLogDtos.LogPage;
import lms.api.sales.dto.SalesLogDtos.LogQuery;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi log vận hành của sales-service. Token, tenant (X-Tenant-Id) và correlation id do RestClient chuyển tiếp. */
public class HttpSalesLogClient implements SalesLogClient {

    private static final String BASE = "/internal/sales/logs";

    private final RestClient client;

    public HttpSalesLogClient(RestClient client) {
        this.client = client;
    }

    @Override
    public LogPage integrationLogs(LogQuery q) {
        return fetch(BASE + "/integration", q);
    }

    @Override
    public LogPage systemLogs(LogQuery q) {
        return fetch(BASE + "/system", q);
    }

    private LogPage fetch(String path, LogQuery q) {
        LogPage page = client.get().uri(b -> {
            b.path(path);
            param(b, "channel", q.channel());
            param(b, "provider", q.provider());
            param(b, "outcome", q.outcome());
            param(b, "level", q.level());
            param(b, "anomalyOnly", q.anomalyOnly());
            param(b, "from", q.from());
            param(b, "to", q.to());
            param(b, "tenantId", q.tenantId());
            param(b, "page", q.page());
            param(b, "size", q.size());
            return b.build();
        }).retrieve().body(LogPage.class);
        return page == null ? new LogPage(List.of(), 0, 0, 0) : page;
    }

    private static void param(UriBuilder b, String name, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return;
        }
        b.queryParam(name, value);
    }
}
