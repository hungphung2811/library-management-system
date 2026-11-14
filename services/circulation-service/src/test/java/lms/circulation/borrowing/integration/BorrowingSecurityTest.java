package lms.circulation.borrowing.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.circulation.borrowing.infrastructure.web.BorrowingController;
import lms.circulation.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm chứng RBAC: không token, thiếu permission, đủ permission. Không cần Docker. */
@WebMvcTest(BorrowingController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class BorrowingSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean BorrowBookUseCase borrowBook;
    @MockitoBean ReturnBookUseCase returnBook;
    @MockitoBean RenewBorrowingUseCase renewBorrowing;
    @MockitoBean GetBorrowingUseCase getBorrowing;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final String BODY = "{\"userId\":\"" + UUID.randomUUID() + "\",\"bookCopyIds\":[\"" + UUID.randomUUID() + "\"]}";

    private BorrowingResponse response() {
        return new BorrowingResponse(UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", LocalDate.now(),
                LocalDate.now().plusDays(14), 0, List.of());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/api/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thieuPermissionBorrowBookBi403() throws Exception {
        mvc.perform(post("/api/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("permissions", List.of("BorrowingController/returnBook")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/returnBook"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void duPermissionBorrowBookDuocTao() throws Exception {
        when(borrowBook.execute(any())).thenReturn(response());

        mvc.perform(post("/api/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("permissions", List.of("BorrowingController/borrow")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/borrow"))))
                .andExpect(status().isCreated());
    }

    @Test
    void xemPhieuMuonChiCanDangNhap() throws Exception {
        when(getBorrowing.execute(any())).thenReturn(response());

        mvc.perform(get("/api/borrowings/" + UUID.randomUUID()).with(jwt()))
                .andExpect(status().isOk());
    }
}
