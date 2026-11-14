package lms.api.circulation;

import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.client.http.HttpCirculationClient;
import lms.api.config.ServiceClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CirculationClientConfiguration {

    @Bean
    CirculationClient circulationClient(ServiceClients clients) {
        return new HttpCirculationClient(clients.create(clients.urls().circulationUrl()));
    }
}
