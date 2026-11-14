package lms.database.config;

import lms.database.api.ConnectionSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bind từ database.* ; key của connections là tên connection (ví dụ USER_DB). */
@ConfigurationProperties(prefix = "database")
public class DatabaseProperties {

    private Map<String, ConnectionSpec> connections = new LinkedHashMap<>();
    /** true: lỗi mở connection làm app dừng; false: ghi nhận FAILED và tiếp tục. */
    private boolean failOnStartupError = true;

    public Map<String, ConnectionSpec> getConnections() { return connections; }
    public void setConnections(Map<String, ConnectionSpec> connections) { this.connections = connections; }
    public boolean isFailOnStartupError() { return failOnStartupError; }
    public void setFailOnStartupError(boolean failOnStartupError) { this.failOnStartupError = failOnStartupError; }
}
