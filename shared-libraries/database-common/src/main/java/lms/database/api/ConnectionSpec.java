package lms.database.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cấu hình của một connection (bind từ database.connections.&lt;NAME&gt;). toString luôn che secret. */
public class ConnectionSpec {

    private DatabaseType type;
    private boolean enabled = true;
    /** Connection critical bị DOWN thì health tổng DOWN; connection phụ (cache...) thì không. */
    private boolean critical = true;

    // Relational (PostgreSQL, Oracle)
    private String url;
    private String username;
    private String password;
    /** Các package chứa @Entity; để trống nghĩa là chỉ dùng JDBC, không tạo EntityManagerFactory. */
    private List<String> entityPackages = new ArrayList<>();
    /** Thuộc tính Hibernate bổ sung, ví dụ hibernate.hbm2ddl.auto. */
    private Map<String, String> jpaProperties = new LinkedHashMap<>();

    // MongoDB
    private String uri;
    /** Tên database Mongo (nếu uri chưa có) hoặc chỉ số database Redis. */
    private String database;

    // Redis
    private String host;
    private Integer port;

    private PoolProperties pool = new PoolProperties();
    private TimeoutProperties timeout = new TimeoutProperties();
    private RetryProperties retry = new RetryProperties();

    public DatabaseType getType() { return type; }
    public void setType(DatabaseType type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isCritical() { return critical; }
    public void setCritical(boolean critical) { this.critical = critical; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public List<String> getEntityPackages() { return entityPackages; }
    public void setEntityPackages(List<String> entityPackages) { this.entityPackages = entityPackages; }
    public Map<String, String> getJpaProperties() { return jpaProperties; }
    public void setJpaProperties(Map<String, String> jpaProperties) { this.jpaProperties = jpaProperties; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public PoolProperties getPool() { return pool; }
    public void setPool(PoolProperties pool) { this.pool = pool; }
    public TimeoutProperties getTimeout() { return timeout; }
    public void setTimeout(TimeoutProperties timeout) { this.timeout = timeout; }
    public RetryProperties getRetry() { return retry; }
    public void setRetry(RetryProperties retry) { this.retry = retry; }

    @Override
    public String toString() {
        return "ConnectionSpec[type=" + type + ", enabled=" + enabled + ", critical=" + critical
                + ", url=" + maskInline(url) + ", username=" + username
                + ", password=" + (password == null ? "null" : "***")
                + ", uri=" + maskUri(uri) + ", host=" + host + ", port=" + port + ", database=" + database + "]";
    }

    private static String maskInline(String value) {
        return value == null ? null : value.replaceAll("(?i)(password|pwd)=[^;&]*", "$1=***");
    }

    private static String maskUri(String value) {
        return value == null ? null : value.replaceAll("://[^/@]*@", "://***@");
    }
}
