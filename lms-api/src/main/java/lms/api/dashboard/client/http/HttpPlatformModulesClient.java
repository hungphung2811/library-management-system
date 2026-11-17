package lms.api.dashboard.client.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lms.api.dashboard.client.PlatformModulesClient;
import org.springframework.web.client.RestClient;

public class HttpPlatformModulesClient implements PlatformModulesClient {

    private final RestClient identity;

    public HttpPlatformModulesClient(RestClient identity) {
        this.identity = identity;
    }

    @Override
    public Set<String> enabledModules(UUID tenantId) {
        JsonNode body = identity.get().uri("/api/platform/tenants/{id}/modules", tenantId).retrieve().body(JsonNode.class);
        Set<String> enabled = new LinkedHashSet<>();
        if (body != null && body.isArray()) {
            for (JsonNode item : body) {
                if (item.path("enabled").asBoolean(false) && item.hasNonNull("module")) {
                    enabled.add(item.get("module").asText().toUpperCase());
                }
            }
        }
        return enabled;
    }
}
