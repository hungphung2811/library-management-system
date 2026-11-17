package lms.identity.user.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import lms.identity.user.application.LookupUsersUseCase;
import lms.identity.user.application.UserNameView;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserRepository;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

/** Tra tên theo lô (ADR-0018): lọc theo tenant, giới hạn lô, không lộ email. Không Spring/DB. */
class LookupUsersUseCaseTest {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    UserRepository users = mock(UserRepository.class);
    LookupUsersUseCase useCase = new LookupUsersUseCase(users);

    @Test
    void chiTraNguoiTrongTenantVaChiCoIdVaHoTen() {
        User inTenant = User.create(null, "an.nguyen", "Nguyễn Văn An", "an@example.com");
        UUID outsider = UUID.randomUUID();
        when(users.findAllInTenant(any(), eq(TENANT))).thenReturn(List.of(inTenant));

        List<UserNameView> result = TenantContext.callAs(TENANT,
                () -> useCase.execute(List.of(inTenant.id(), outsider)));

        assertThat(result).containsExactly(new UserNameView(inTenant.id(), "Nguyễn Văn An"));
        assertThat(UserNameView.class.getRecordComponents()).extracting("name").containsExactly("id", "fullName");
    }

    @Test
    void idTrungDuocGomLai() {
        UUID id = UUID.randomUUID();
        when(users.findAllInTenant(any(), any())).thenReturn(List.of());
        TenantContext.callAs(TENANT, () -> useCase.execute(List.of(id, id)));
        verify(users).findAllInTenant(Set.of(id), TENANT);
    }

    @Test
    void danhSachRongKhongChamKho() {
        assertThat(TenantContext.callAs(TENANT, () -> useCase.execute(List.of()))).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void vuotQuaGioiHanLoBiTuChoi() {
        List<UUID> ids = IntStream.range(0, LookupUsersUseCase.MAX_IDS + 1).mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> TenantContext.callAs(TENANT, () -> useCase.execute(ids)))
                .isInstanceOf(InvalidUserException.class);
        verifyNoInteractions(users);
    }

    @Test
    void khongCoTenantThiTuChoi() {
        assertThatThrownBy(() -> useCase.execute(List.of(UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(users);
    }
}
