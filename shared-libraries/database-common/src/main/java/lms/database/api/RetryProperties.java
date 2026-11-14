package lms.database.api;

import java.time.Duration;

/** Retry khi khởi tạo connection: thử tối đa maxAttempts lần, chờ backoff nhân multiplier sau mỗi lần. */
public class RetryProperties {

    private int maxAttempts = 3;
    private Duration backoff = Duration.ofSeconds(1);
    private double multiplier = 2.0;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getBackoff() { return backoff; }
    public void setBackoff(Duration backoff) { this.backoff = backoff; }
    public double getMultiplier() { return multiplier; }
    public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
}
