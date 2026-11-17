package lms.api.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Máy chủ HTTP thật (JDK) đóng vai catalog, circulation, sales, identity trong một cổng: ghi lại từng request (đường
 * dẫn, Authorization, X-Tenant-Id, tên luồng xử lý) để test chứng minh token và header tới được từng nguồn.
 * Dùng HTTP thật vì MockRestServiceServer không phù hợp để kiểm lời gọi song song trên nhiều luồng.
 */
final class FakeServices implements AutoCloseable {

    record Hit(String method, String path, String query, String authorization, String tenantHeader, String correlationId) {
    }

    record Reply(int status, String contentType, String body) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body);
        }

        static Reply error(int status, String code) {
            return new Reply(status, "application/json", "{\"code\":\"" + code + "\",\"message\":\"x\"}");
        }
    }

    private final HttpServer server;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();

    FakeServices() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Khóa route là "METHOD /đường/dẫn" (bỏ query). */
    FakeServices route(String key, Reply reply) {
        routes.put(key, x -> reply);
        return this;
    }

    FakeServices route(String key, Function<HttpExchange, Reply> reply) {
        routes.put(key, reply);
        return this;
    }

    List<Hit> hits() {
        return List.copyOf(hits);
    }

    List<Hit> hitsTo(String pathPrefix) {
        return hits.stream().filter(h -> h.path().startsWith(pathPrefix)).toList();
    }

    void reset() {
        hits.clear();
        routes.clear();
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        ex.getRequestBody().readAllBytes();
        hits.add(new Hit(method, path, ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst("X-Tenant-Id"), ex.getRequestHeaders().getFirst("X-Correlation-Id")));
        Function<HttpExchange, Reply> route = routes.get(method + " " + path);
        if (route == null) {
            // đường có tham số cuối ({id}) khớp theo tiền tố đăng ký bằng dấu *
            route = routes.entrySet().stream().filter(e -> e.getKey().endsWith("*")
                            && (method + " " + path).startsWith(e.getKey().substring(0, e.getKey().length() - 1)))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        Reply reply = route == null ? Reply.error(404, "NOT_FOUND") : route.apply(ex);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", reply.contentType());
        ex.sendResponseHeaders(reply.status(), bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
