package lms.api.dashboard.service;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Chép ngữ cảnh của luồng request sang luồng con (ADR-0018 mục 2.2). {@code TokenRelayInterceptor} lấy Bearer từ
 * {@code SecurityContextHolder} và {@code X-Tenant-Id} từ {@code RequestContextHolder}, cả hai là ThreadLocal: thiếu
 * decorator này thì mọi lời gọi song song mất token (401) và mất header tenant (403 TENANT_REQUIRED).
 *
 * <p>Chép: SecurityContext (bản sao, cùng Authentication), RequestAttributes (cùng request), MDC (correlationId).
 * Xóa sạch ở luồng con khi chạy xong để luồng pool không giữ token của người khác.
 */
public class ContextCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        SecurityContext captured = SecurityContextHolder.createEmptyContext();
        captured.setAuthentication(SecurityContextHolder.getContext().getAuthentication());
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            try {
                SecurityContextHolder.setContext(captured);
                if (attributes != null) {
                    RequestContextHolder.setRequestAttributes(attributes);
                }
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                task.run();
            } finally {
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
                MDC.clear();
            }
        };
    }
}
