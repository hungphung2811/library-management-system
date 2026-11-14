package lms.migration.infrastructure.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * migration.targets.&lt;tên&gt;.{url,username,password,locations}; thứ tự khai báo = thứ tự chạy.
 */
@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(
        @DefaultValue("true") boolean runOnStartup,
        @DefaultValue("true") boolean failFast,
        @DefaultValue Map<String, Target> targets) {

    public record Target(String url, String username, String password, List<String> locations) {
    }
}
