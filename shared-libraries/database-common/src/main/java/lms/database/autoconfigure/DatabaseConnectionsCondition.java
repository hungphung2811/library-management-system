package lms.database.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Chỉ bật khi có ít nhất một thuộc tính database.connections.*, để service khác không bị ảnh hưởng. */
class DatabaseConnectionsCondition extends SpringBootCondition {

    private static final ConfigurationPropertyName NAME = ConfigurationPropertyName.of("database.connections");

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(context.getEnvironment())) {
            if (source.containsDescendantOf(NAME) == ConfigurationPropertyState.PRESENT) {
                return ConditionOutcome.match("Có cấu hình database.connections");
            }
        }
        return ConditionOutcome.noMatch("Không có cấu hình database.connections");
    }
}
