package lms.identity.apiclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews;
import org.junit.jupiter.api.Test;

/**
 * Rò rỉ bí mật qua DTO (SaaS API Authentication): chỉ {@code CreatedCredential} được mang clientSecret; mọi DTO dùng cho
 * GET (chi tiết, danh sách, credential, lịch sử) không có trường secret/hash/password, và toString của CreatedCredential
 * che secret (tránh lọt vào log).
 */
class ApplicationViewsSecretLeakTest {

    @Test
    void chiCreatedCredentialMangSecret() {
        List<String> leaks = new ArrayList<>();
        for (Class<?> view : ApplicationViews.class.getDeclaredClasses()) {
            if (!view.isRecord() || view == ApplicationViews.CreatedCredential.class) continue;
            for (RecordComponent c : view.getRecordComponents()) {
                String n = c.getName().toLowerCase(Locale.ROOT);
                if (n.contains("secret") && !n.equals("secrethint") || n.contains("hash") || n.contains("password")) {
                    leaks.add(view.getSimpleName() + "." + c.getName());
                }
            }
        }
        assertThat(leaks).as("DTO GET không được có trường secret/hash").isEmpty();
    }

    @Test
    void toStringCuaCreatedCredentialCheSecret() {
        String secret = "lms_cs_SUPER-SECRET-VALUE-0123456789abcdefghijklmnop";
        ApplicationViews.CreatedCredential created = new ApplicationViews.CreatedCredential(UUID.randomUUID(),
                UUID.randomUUID(), secret, "mnop", Instant.now(), Instant.now().plusSeconds(60));
        assertThat(created.toString()).doesNotContain(secret).doesNotContain("SUPER-SECRET");
    }
}
