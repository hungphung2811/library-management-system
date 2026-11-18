package lms.identity.user.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.application.TenantNameQuery;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.user.application.LookupNotificationRecipientsUseCase;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tra người nhận thông báo (ADR-0030 D5): ưu tiên email hồ sơ độc giả của tenant, chỉ membership ACTIVE, dữ liệu tối thiểu. */
class LookupNotificationRecipientsUseCaseTest {

    private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UserRepository users = mock(UserRepository.class);
    private final ReaderProfileRepository readers = mock(ReaderProfileRepository.class);
    private final TenantNameQuery names = mock(TenantNameQuery.class);
    private final LookupNotificationRecipientsUseCase useCase =
            new LookupNotificationRecipientsUseCase(users, readers, names, new TransactionalRunner());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static User user(String username, String name, String email, UserStatus status) {
        return User.restore(UUID.randomUUID(), username, name, email, status);
    }

    @Test
    void uuTienEmailVaTenHoSoDocGiaCuaTenantRoiMoiDenUsers() {
        User withReader = user("reader1", "Tên Users A", "users.a@example.com", UserStatus.ACTIVE);
        User plain = user("staff1", "Nhân Viên B", "staff.b@example.com", UserStatus.ACTIVE);
        ReaderProfile profile = ReaderProfile.restore(withReader.id(), "TH-001", "Độc Giả A", "reader.a@example.com",
                ReaderType.STUDENT, LocalDate.of(2030, 1, 1), lms.identity.user.domain.ReaderStatus.ACTIVE);
        when(names.nameOf(tenant)).thenReturn(Optional.of("Hiệu sách A"));
        when(users.findAllActiveInTenant(any(), any())).thenReturn(List.of(withReader, plain));
        when(readers.findByUserIds(any())).thenReturn(List.of(profile));

        var result = useCase.execute(tenant, List.of(withReader.id(), plain.id()));

        assertThat(result.tenantDisplayName()).isEqualTo("Hiệu sách A");
        assertThat(result.recipients()).hasSize(2);
        var r1 = result.recipients().stream().filter(r -> r.userId().equals(withReader.id())).findFirst().get();
        assertThat(r1.email()).isEqualTo("reader.a@example.com");
        assertThat(r1.displayName()).isEqualTo("Độc Giả A");
        assertThat(r1.active()).isTrue();
        var r2 = result.recipients().stream().filter(r -> r.userId().equals(plain.id())).findFirst().get();
        assertThat(r2.email()).isEqualTo("staff.b@example.com");
        assertThat(r2.displayName()).isEqualTo("Nhân Viên B");
    }

    @Test
    void chiHoiMotTruyVanChoCaLoKhongHoiTungNguoi() {
        List<UUID> ids = new ArrayList<>();
        List<User> found = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            User u = user("user" + (100 + i), "U" + i, "u" + i + "@example.com", UserStatus.ACTIVE);
            ids.add(u.id());
            found.add(u);
        }
        when(users.findAllActiveInTenant(any(), any())).thenReturn(found);
        when(readers.findByUserIds(any())).thenReturn(List.of());
        assertThat(useCase.execute(tenant, ids).recipients()).hasSize(100);
        verify(users).findAllActiveInTenant(any(), any());
        verify(readers).findByUserIds(any());
        verify(readers, never()).findByUserId(any());
    }

    @Test
    void nguoiDungBiVoHieuHoaTraActiveFalse() {
        User disabled = user("disabled1", "Bị Khóa", "khoa@example.com", UserStatus.DISABLED);
        when(users.findAllActiveInTenant(any(), any())).thenReturn(List.of(disabled));
        when(readers.findByUserIds(any())).thenReturn(List.of());
        assertThat(useCase.execute(tenant, List.of(disabled.id())).recipients())
                .singleElement().satisfies(r -> assertThat(r.active()).isFalse());
    }

    @Test
    void idNgoaiTenantKhongCoTrongKetQuaVaTruyVanDungTenantDuocHoi() {
        UUID outsider = UUID.randomUUID();
        when(users.findAllActiveInTenant(any(), any())).thenReturn(List.of()); // membership ACTIVE lọc ở repository
        when(readers.findByUserIds(any())).thenReturn(List.of());
        assertThat(useCase.execute(tenant, List.of(outsider)).recipients()).isEmpty();
        verify(users).findAllActiveInTenant(org.mockito.ArgumentMatchers.argThat((Collection<UUID> c) -> c.contains(outsider)),
                org.mockito.ArgumentMatchers.eq(tenant));
    }

    @Test
    void chayTrongTenantContextCuaTenantDuocHoiVaKhoiPhucSauKhiXong() {
        UUID caller = UUID.randomUUID();
        TenantContext.set(caller);
        UUID[] seen = new UUID[1];
        when(users.findAllActiveInTenant(any(), any())).thenAnswer(i -> {
            seen[0] = TenantContext.current().orElse(null);
            return List.of();
        });
        when(readers.findByUserIds(any())).thenReturn(List.of());
        useCase.execute(tenant, List.of(UUID.randomUUID()));
        assertThat(seen[0]).isEqualTo(tenant);
        assertThat(TenantContext.current()).contains(caller);
    }

    @Test
    void khongCoIdKhongTruyVanDbChiTraTenTenant() {
        when(names.nameOf(tenant)).thenReturn(Optional.of("Hiệu sách A"));
        assertThat(useCase.execute(tenant, List.of()).recipients()).isEmpty();
        assertThat(useCase.execute(tenant, null).tenantDisplayName()).isEqualTo("Hiệu sách A");
        verify(users, never()).findAllActiveInTenant(any(), any());
    }

    @Test
    void quaMotTramIdHoacIdNullHoacThieuTenantBiTuChoi() {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID());
        assertThatThrownBy(() -> useCase.execute(tenant, tooMany)).isInstanceOf(InvalidUserException.class);
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> useCase.execute(tenant, withNull)).isInstanceOf(InvalidUserException.class);
        assertThatThrownBy(() -> useCase.execute(null, List.of())).isInstanceOf(InvalidUserException.class);
    }

    @Test
    void ketQuaToStringKhongLoEmail() {
        var r = new LookupNotificationRecipientsUseCase.Recipient(UUID.randomUUID(), "bi.mat@example.com", "Tên", true);
        assertThat(r.toString()).doesNotContain("bi.mat");
    }
}
