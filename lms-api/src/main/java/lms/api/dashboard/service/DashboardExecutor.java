package lms.api.dashboard.service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool riêng cho gọi song song của dashboard, luôn có {@link ContextCopyingTaskDecorator}. Cố ý KHÔNG là bean kiểu
 * {@code Executor}: bean Executor của người dùng làm Spring Boot bỏ {@code applicationTaskExecutor} mặc định.
 */
public final class DashboardExecutor implements DisposableBean {

    private final ThreadPoolTaskExecutor pool;

    public DashboardExecutor(int coreSize, int maxSize, int queueCapacity) {
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(coreSize);
        pool.setMaxPoolSize(maxSize);
        pool.setQueueCapacity(queueCapacity);
        pool.setThreadNamePrefix("dashboard-");
        pool.setTaskDecorator(new ContextCopyingTaskDecorator());
        pool.initialize();
    }

    /** Chạy trên luồng pool với ngữ cảnh của luồng gọi. Ném RejectedExecutionException khi pool đầy. */
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, pool);
    }

    @Override
    public void destroy() {
        pool.shutdown();
    }
}
