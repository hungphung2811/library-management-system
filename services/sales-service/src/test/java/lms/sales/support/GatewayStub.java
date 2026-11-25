package lms.sales.support;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Máy chủ HTTP giả nội bộ cho test cổng thanh toán: mọi đường dẫn trả cùng một mã/thân/header cấu hình được. */
public final class GatewayStub implements AutoCloseable {

    private final HttpServer server;
    public volatile int status = 200;
    public volatile String body = "{}";
    public final Map<String, String> headers = new ConcurrentHashMap<>();
    public final AtomicInteger calls = new AtomicInteger();

    public GatewayStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                calls.incrementAndGet();
                ex.getRequestBody().readAllBytes();
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
