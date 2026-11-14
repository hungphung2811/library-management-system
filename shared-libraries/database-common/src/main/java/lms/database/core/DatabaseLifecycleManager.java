package lms.database.core;

import lms.database.config.DatabaseProperties;
import lms.database.config.DatabasePropertiesValidator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;

/**
 * Validate rồi mở mọi connection ngay khi bean được tạo (để bean của service như EntityManagerFactory,
 * repository dùng được trong lúc khởi động), và đóng khi shutdown. Phase thấp để dừng SAU web server,
 * tức là request đang xử lý xong rồi mới đóng pool.
 */
public class DatabaseLifecycleManager implements InitializingBean, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLifecycleManager.class);

    private final DatabaseConnectionManager manager;
    private final DatabaseProperties properties;
    private final DatabasePropertiesValidator validator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DatabaseLifecycleManager(DatabaseConnectionManager manager, DatabaseProperties properties,
                                    DatabasePropertiesValidator validator) {
        this.manager = manager;
        this.properties = properties;
        this.validator = validator;
    }

    @Override
    public void afterPropertiesSet() {
        validator.validate(properties.getConnections(), manager.supportedTypes());
        try {
            manager.initializeConnections(properties.getConnections(), properties.isFailOnStartupError());
        } catch (RuntimeException e) {
            // Không để rò connection đã mở khi một connection sau đó lỗi.
            manager.closeAllConnections();
            throw e;
        }
        running.set(true);
    }

    @Override
    public void start() {
        // Đã mở trong afterPropertiesSet.
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Đóng toàn bộ database connection");
            manager.closeAllConnections();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
