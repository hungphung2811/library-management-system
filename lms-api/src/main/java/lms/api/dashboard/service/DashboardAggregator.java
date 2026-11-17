package lms.api.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.dto.SummaryView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gộp {@code GET /api/dashboard/summary} (ADR-0018 mục 2.1, 2.2): gọi song song catalog, circulation, sales với hạn chót
 * chung, rồi xếp phần lỗi vào disabledModules, forbiddenSections, unavailableSections. Quy tắc theo thứ tự:
 * lỗi mức người gọi (401, 403 TENANT_REQUIRED, 400 INVALID_TENANT_ID) ném lại nguyên; mọi nguồn đã gọi đều 403
 * thì 403 FORBIDDEN; mọi nguồn đã gọi đều hỏng thì 503 SERVICE_UNAVAILABLE; còn lại 200.
 *
 * <p>Chạy trên {@link DashboardExecutor} (có TaskDecorator chép token và X-Tenant-Id). Mọi future được chờ xong hoặc
 * hủy trước khi trả, nên luồng con không đọc request sau khi request kết thúc, trừ lời gọi mạng đã bắt đầu
 * (header đã đọc ngay khi bắt đầu gọi).
 */
public class DashboardAggregator {

    private static final Logger log = LoggerFactory.getLogger(DashboardAggregator.class);
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String LENDING = "LENDING";
    static final String SALES = "SALES";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DashboardStatsClient stats;
    private final ModuleResolver modules;
    private final TitleNameEnricher titles;
    private final DashboardExecutor executor;
    private final Clock clock;
    private final Duration deadline;

    public DashboardAggregator(DashboardStatsClient stats, ModuleResolver modules, TitleNameEnricher titles,
                               DashboardExecutor executor, Clock clock, Duration deadline) {
        this.stats = stats;
        this.modules = modules;
        this.titles = titles;
        this.executor = executor;
        this.clock = clock.withZone(BUSINESS_ZONE);
        this.deadline = deadline;
    }

    public SummaryView summary(Jwt jwt, String tenantHeader) {
        long deadlineAt = System.nanoTime() + deadline.toNanos();
        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        LocalDate asOf = generatedAt.toLocalDate();
        ModuleScope scope = modules.resolve(jwt, tenantHeader);

        boolean callLending = scope.allows(LENDING) && !scope.lookupFailed();
        boolean callSales = scope.allows(SALES) && !scope.lookupFailed();
        // Mọi lời gọi submit từ luồng request: decorator chụp SecurityContext/RequestAttributes/MDC tại đây
        CompletableFuture<Outcome> catalogF = submit(stats::catalogSummary);
        CompletableFuture<Outcome> lendingF = callLending ? submit(() -> stats.lendingSummary(asOf)) : null;
        CompletableFuture<Outcome> salesF = callSales ? submit(() -> stats.salesSummary(asOf)) : null;

        Outcome catalog = await(catalogF, deadlineAt);
        Outcome lending = lendingF != null ? await(lendingF, deadlineAt)
                : scope.lookupFailed() && scope.allows(LENDING) ? Outcome.unavailable() : null;
        Outcome sales = salesF != null ? await(salesF, deadlineAt)
                : scope.lookupFailed() && scope.allows(SALES) ? Outcome.unavailable() : null;

        List<Outcome> called = new ArrayList<>();
        for (Outcome o : new Outcome[] {catalog, lending, sales}) {
            if (o != null) {
                called.add(o);
            }
        }
        for (Outcome o : called) {
            if (o.callerLevelError()) {
                throw o.error;
            }
        }

        List<String> disabled = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (scope.known() && !scope.enabled().contains(LENDING)) {
            disabled.add(LENDING);
        }
        if (scope.known() && !scope.enabled().contains(SALES)) {
            disabled.add(SALES);
        }

        JsonNode catalogBody = place("catalog", catalog, disabled, forbidden, unavailable, null);
        JsonNode lendingRoot = place("lending", lending, disabled, forbidden, unavailable, LENDING);
        JsonNode salesBody = place("sales", sales, disabled, forbidden, unavailable, SALES);
        if (lending != null && lendingRoot == null && lending.kind() != Kind.DISABLED_MODULE) {
            // fines đi cùng nguồn lending: cùng một trạng thái lỗi
            (lending.kind() == Kind.FORBIDDEN ? forbidden : unavailable).add("fines");
        }
        JsonNode lendingBody = lendingRoot == null ? null : lendingRoot.get("lending");
        JsonNode finesBody = lendingRoot == null ? null : lendingRoot.get("fines");

        if (!called.isEmpty() && called.stream().allMatch(o -> o.kind() == Kind.FORBIDDEN)) {
            throw new DashboardException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền xem dashboard");
        }
        if (!called.isEmpty() && called.stream().allMatch(o -> o.kind() == Kind.UNAVAILABLE)) {
            throw new DashboardException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Dịch vụ thống kê tạm thời không khả dụng");
        }

        if (catalogBody instanceof ObjectNode node && disabled.contains(LENDING)) {
            node.putNull("copies"); // số bản sao thuộc module LENDING
        }
        if (salesBody != null && System.nanoTime() < deadlineAt) {
            titles.fill(salesBody.get("lowStockItems"));
        }

        List<String> enabledModules = new ArrayList<>(List.of(LENDING, SALES));
        enabledModules.removeAll(disabled);
        return new SummaryView(generatedAt, BUSINESS_ZONE.getId(), scope.tenantId(), enabledModules, catalogBody,
                lendingBody, finesBody, salesBody, disabled, forbidden, unavailable);
    }

    private static JsonNode place(String section, Outcome outcome, List<String> disabled, List<String> forbidden,
                                  List<String> unavailable, String module) {
        if (outcome == null) {
            return null;
        }
        switch (outcome.kind()) {
            case OK:
                return outcome.body;
            case DISABLED_MODULE:
                if (module != null && !disabled.contains(module)) {
                    disabled.add(module);
                }
                return null;
            case FORBIDDEN:
                forbidden.add(section);
                return null;
            default:
                unavailable.add(section);
                return null;
        }
    }

    private CompletableFuture<Outcome> submit(Supplier<JsonNode> call) {
        try {
            return executor.submit(() -> Outcome.of(call));
        } catch (RejectedExecutionException ex) {
            log.warn("Pool dashboard đầy, bỏ qua một nguồn");
            return CompletableFuture.completedFuture(Outcome.unavailable());
        }
    }

    private static Outcome await(CompletableFuture<Outcome> future, long deadlineAt) {
        try {
            long remaining = Math.max(0, deadlineAt - System.nanoTime());
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Nguồn thống kê quá hạn chót chung của summary");
            return Outcome.unavailable();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Outcome.unavailable();
        } catch (ExecutionException ex) {
            return Outcome.unavailable();
        }
    }

    private enum Kind { OK, DISABLED_MODULE, FORBIDDEN, UNAVAILABLE }

    /** Kết quả của một nguồn: thân JSON, hoặc lỗi HTTP của service, hoặc không khả dụng (5xx, mất kết nối, quá hạn). */
    private record Outcome(JsonNode body, RestClientResponseException error, boolean failed) {

        static Outcome of(Supplier<JsonNode> call) {
            try {
                return new Outcome(call.get(), null, false);
            } catch (RestClientResponseException ex) {
                return new Outcome(null, ex, true);
            } catch (RuntimeException ex) {
                log.warn("Nguồn thống kê lỗi: {}", ex.toString());
                return unavailable();
            }
        }

        static Outcome unavailable() {
            return new Outcome(null, null, true);
        }

        int status() {
            return error == null ? 0 : error.getStatusCode().value();
        }

        String code() {
            if (error == null) {
                return null;
            }
            try {
                JsonNode node = JSON.readTree(error.getResponseBodyAsString());
                return node.hasNonNull("code") ? node.get("code").asText() : null;
            } catch (Exception ex) {
                return null;
            }
        }

        boolean callerLevelError() {
            if (error == null) {
                return false;
            }
            int status = status();
            String code = status == 403 || status == 400 ? code() : null;
            return status == 401 || (status == 403 && "TENANT_REQUIRED".equals(code))
                    || (status == 400 && "INVALID_TENANT_ID".equals(code));
        }

        Kind kind() {
            if (!failed) {
                return Kind.OK;
            }
            if (status() == 403) {
                return "MODULE_NOT_ENABLED".equals(code()) ? Kind.DISABLED_MODULE : Kind.FORBIDDEN;
            }
            return Kind.UNAVAILABLE;
        }
    }
}
