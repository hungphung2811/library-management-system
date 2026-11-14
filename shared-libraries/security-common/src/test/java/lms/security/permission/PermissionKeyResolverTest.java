package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.security.permission.PermissionKeyResolver.Mode;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class PermissionKeyResolverTest {

    static class PlainController {
        public void list() { }

        @RequirePermission("ORDER_VIEW")
        public void custom() { }

        @RequirePermission(description = "Mô tả riêng")
        public void autoWithDescription() { }

        @AuthenticatedOnly
        public void mine() { }

        @PublicEndpoint
        public void open() { }

        @RequirePermission("khoa sai")
        public void invalid() { }
    }

    @AuthenticatedOnly
    static class MostlyOpenController {
        public void a() { }

        @RequirePermission("SPECIAL")
        public void b() { }
    }

    private final PermissionKeyResolver resolver = new PermissionKeyResolver();

    private PermissionKeyResolver.Resolved resolve(Class<?> type, String method) throws Exception {
        return resolver.resolve(new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getMethod(method)));
    }

    @Test
    void khongAnnotationThiTuSinhTenControllerSlashTenPhuongThuc() throws Exception {
        var resolved = resolve(PlainController.class, "list");

        assertThat(resolved.mode()).isEqualTo(Mode.PERMISSION);
        assertThat(resolved.key()).isEqualTo("PlainController/list");
        assertThat(resolved.source()).isEqualTo("AUTO");
    }

    @Test
    void customKeyDuocUuTien() throws Exception {
        var resolved = resolve(PlainController.class, "custom");

        assertThat(resolved.key()).isEqualTo("ORDER_VIEW");
        assertThat(resolved.source()).isEqualTo("CUSTOM");
    }

    @Test
    void requirePermissionKhongCoKeyVanTuSinhNhungGiuMoTa() throws Exception {
        var resolved = resolve(PlainController.class, "autoWithDescription");

        assertThat(resolved.key()).isEqualTo("PlainController/autoWithDescription");
        assertThat(resolved.source()).isEqualTo("AUTO");
        assertThat(resolved.description()).isEqualTo("Mô tả riêng");
    }

    @Test
    void cheDoAuthenticatedVaPublic() throws Exception {
        assertThat(resolve(PlainController.class, "mine").mode()).isEqualTo(Mode.AUTHENTICATED);
        assertThat(resolve(PlainController.class, "open").mode()).isEqualTo(Mode.PUBLIC);
    }

    @Test
    void annotationOMethodThangAnnotationOClass() throws Exception {
        assertThat(resolve(MostlyOpenController.class, "a").mode()).isEqualTo(Mode.AUTHENTICATED);
        var special = resolve(MostlyOpenController.class, "b");
        assertThat(special.mode()).isEqualTo(Mode.PERMISSION);
        assertThat(special.key()).isEqualTo("SPECIAL");
    }

    @Test
    void customKeyChuaKyTuKhongHopLeBiTuChoiRoRang() {
        assertThatThrownBy(() -> resolve(PlainController.class, "invalid"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("customKey không hợp lệ");
    }
}
