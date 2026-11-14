package lms.database.api;

import java.time.Duration;

/** Cấu hình pool cho connection quan hệ (HikariCP) và Mongo. Redis dùng một kết nối Lettuce dùng chung. */
public class PoolProperties {

    private int maxSize = 10;
    private int minIdle = 2;
    private Duration idleTimeout = Duration.ofMinutes(10);
    private Duration maxLifetime = Duration.ofMinutes(30);

    public int getMaxSize() { return maxSize; }
    public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    public Duration getMaxLifetime() { return maxLifetime; }
    public void setMaxLifetime(Duration maxLifetime) { this.maxLifetime = maxLifetime; }
}
