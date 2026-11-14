package lms.security.permission;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.web.client.RestClient;

/**
 * Đăng ký khóa bằng REST nội bộ tới identity-service (mỗi service có DB riêng nên không ghi thẳng vào DB của identity).
 * Bảo vệ bằng khóa chia sẻ trong header; endpoint này không được route qua gateway.
 */
public class RemotePermissionKeyRegistrar extends CachingPermissionKeyRegistrar {

    private final RestClient client;
    private final String apiKey;

    public RemotePermissionKeyRegistrar(RestClient client, String apiKey, Executor executor, Clock clock,
                                        Duration cooldown) {
        super(executor, clock, cooldown);
        this.client = client;
        this.apiKey = apiKey;
    }

    @Override
    protected void doRegister(EndpointDescriptor descriptor) {
        client.post()
                .uri(EndpointDescriptor.REGISTRATION_PATH)
                .header(EndpointDescriptor.INTERNAL_KEY_HEADER, apiKey)
                .body(descriptor)
                .retrieve()
                .toBodilessEntity();
    }
}
