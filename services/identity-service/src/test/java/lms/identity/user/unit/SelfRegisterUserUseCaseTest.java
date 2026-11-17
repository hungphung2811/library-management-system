package lms.identity.user.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.SelfRegisterUserUseCase;
import lms.identity.user.application.SelfRegistrationRejectedException;
import lms.identity.user.application.SelfRegistrationRejectedException.Reason;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tự đăng ký chỉ chuyển 4 trường allowlist: không phòng ban, không id, không buộc đổi mật khẩu. */
class SelfRegisterUserUseCaseTest {

    private final CreateUserUseCase create = mock(CreateUserUseCase.class);
    private final SelfRegisterUserUseCase useCase = new SelfRegisterUserUseCase(create);

    @Test
    void chiChuyenBonTruongVaDatMacDinhAnToan() {
        UUID id = UUID.randomUUID();
        when(create.execute(any())).thenReturn(new UserResponse(id, "bob", "Bob", "bob@example.com", null,
                UserStatus.ACTIVE));

        assertThat(useCase.execute("bob", "secret123", "Bob", "bob@example.com")).isEqualTo(id);

        ArgumentCaptor<CreateUserRequest> c = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(create).execute(c.capture());
        CreateUserRequest r = c.getValue();
        assertThat(r.id()).isNull();                       // id sinh mới
        assertThat(r.departmentId()).isNull();             // không phòng ban
        assertThat(r.mustChangePassword()).isFalse();      // mật khẩu do khách tự đặt
        assertThat(r.password()).isEqualTo("secret123");
        assertThat(r.username()).isEqualTo("bob");
    }

    @Test
    void trungDichSangLoiApplicationVoiThongDiepChung() {
        when(create.execute(any())).thenThrow(DuplicateUserException.generic());

        assertThatThrownBy(() -> useCase.execute("bob", "secret123", "Bob", "bob@example.com"))
                .isInstanceOfSatisfying(SelfRegistrationRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.DUPLICATE);
                    assertThat(e.getMessage()).isEqualTo(DuplicateUserException.GENERIC_MESSAGE);
                });
    }

    @Test
    void khongHopLeDichSangInvalid() {
        when(create.execute(any())).thenThrow(new InvalidUserException("Email không hợp lệ"));

        assertThatThrownBy(() -> useCase.execute("bob", "secret123", "Bob", "x"))
                .isInstanceOfSatisfying(SelfRegistrationRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.INVALID);
                    assertThat(e.getMessage()).isEqualTo("Email không hợp lệ");
                });
    }
}
