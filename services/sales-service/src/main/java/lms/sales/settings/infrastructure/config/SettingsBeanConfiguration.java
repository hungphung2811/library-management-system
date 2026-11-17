package lms.sales.settings.infrastructure.config;

import java.time.Clock;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context settings. */
@Configuration
public class SettingsBeanConfiguration {

    @Bean
    GetSalesSettingsUseCase getSalesSettingsUseCase(SalesSettingsPort port) {
        return new GetSalesSettingsUseCase(port);
    }

    @Bean
    UpdateSalesSettingsUseCase updateSalesSettingsUseCase(SalesSettingsPort port, SalesAuditPort audit, Clock clock) {
        return new UpdateSalesSettingsUseCase(port, audit, clock);
    }
}
