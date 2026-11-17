package lms.customer.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Bảo vệ và đo lời gọi sang một service phía sau:
 *
 * <ul>
 *   <li>Bulkhead: {@link Semaphore} giới hạn lời gọi đồng thời tới đích; hết chỗ thì 503 ngay (không xếp hàng).
 *       Chỗ được trả khi response được đóng (thân đã đọc xong), nên đích trả chậm thân cũng bị tính.</li>
 *   <li>Retry đúng 1 lần, chỉ cho GET/HEAD (idempotent): lỗi kết nối (không phải hết thời gian đọc, để khỏi nhân đôi
 *       thời gian chờ) hoặc phản hồi 502/503/504.</li>
 *   <li>Metric: Timer {@code customer.downstream.calls} (tag target, method, outcome), counter
 *       {@code customer.downstream.rejected} (bulkhead) và {@code customer.downstream.retries}. Tag chỉ lấy từ tập đóng,
 *       không chứa mã tenant, đường dẫn hay dữ liệu khách (tránh cardinality nổ và rò rỉ).</li>
 * </ul>
 */
public class DownstreamCallInterceptor implements ClientHttpRequestInterceptor {

    private final String target;
    private final Semaphore permits;
    private final MeterRegistry registry;
    private final Counter rejected;
    private final Counter retries;

    public DownstreamCallInterceptor(String target, Semaphore permits, MeterRegistry registry) {
        this.target = target;
        this.permits = permits;
        this.registry = registry;
        this.rejected = Counter.builder("customer.downstream.rejected").tag("target", target)
                .description("Lời gọi bị từ chối vì hết chỗ bulkhead").register(registry);
        this.retries = Counter.builder("customer.downstream.retries").tag("target", target)
                .description("Số lần retry GET idempotent").register(registry);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!permits.tryAcquire()) {
            rejected.increment();
            throw PublicApiException.serviceUnavailable();
        }
        long start = System.nanoTime();
        String outcome = "error";
        boolean handedOver = false;
        try {
            ClientHttpResponse response = executeWithRetry(request, body, execution);
            outcome = outcomeOf(response.getStatusCode());
            handedOver = true;
            return new PermitReleasingResponse(response, permits);
        } finally {
            if (!handedOver) {
                permits.release();
            }
            Timer.builder("customer.downstream.calls").tag("target", target)
                    .tag("method", methodOf(request)).tag("outcome", outcome)
                    .description("Thời gian lời gọi sang service phía sau (tới khi có header phản hồi)")
                    .register(registry).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private ClientHttpResponse executeWithRetry(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        boolean idempotent = isIdempotent(request);
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException ex) {
            if (!idempotent || isReadTimeout(ex)) {
                throw ex;
            }
            retries.increment();
            return execution.execute(request, body);
        }
        if (idempotent && isRetryableStatus(response.getStatusCode())) {
            response.close();
            retries.increment();
            return execution.execute(request, body);
        }
        return response;
    }

    private static boolean isIdempotent(HttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod()) || HttpMethod.HEAD.equals(request.getMethod());
    }

    private static boolean isReadTimeout(IOException ex) {
        return ex instanceof HttpTimeoutException && !(ex instanceof HttpConnectTimeoutException);
    }

    private static boolean isRetryableStatus(HttpStatusCode status) {
        int code = status.value();
        return code == 502 || code == 503 || code == 504;
    }

    private static String outcomeOf(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return "success";
        }
        if (status.is4xxClientError()) {
            return "client_error";
        }
        return status.is5xxServerError() ? "server_error" : "other";
    }

    private static String methodOf(HttpRequest request) {
        HttpMethod method = request.getMethod();
        return HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method) || HttpMethod.HEAD.equals(method)
                ? method.name() : "OTHER";
    }

    /** Trả chỗ bulkhead đúng một lần khi response được đóng. */
    private static final class PermitReleasingResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        PermitReleasingResponse(ClientHttpResponse delegate, Semaphore permits) {
            this.delegate = delegate;
            this.permits = permits;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public void close() {
            try {
                delegate.close();
            } finally {
                if (released.compareAndSet(false, true)) {
                    permits.release();
                }
            }
        }
    }
}
