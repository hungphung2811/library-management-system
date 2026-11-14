package lms.database.api;

import java.time.Duration;

public class TimeoutProperties {

    private Duration connect = Duration.ofSeconds(5);
    private Duration validation = Duration.ofSeconds(3);

    public Duration getConnect() { return connect; }
    public void setConnect(Duration connect) { this.connect = connect; }
    public Duration getValidation() { return validation; }
    public void setValidation(Duration validation) { this.validation = validation; }
}
