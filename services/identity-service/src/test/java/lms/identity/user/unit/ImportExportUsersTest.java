package lms.identity.user.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.DepartmentLookupPort;
import lms.identity.user.application.ImportUserRowExecutor;
import lms.identity.user.application.ImportUsersRequest;
import lms.identity.user.application.ImportUsersRequest.Row;
import lms.identity.user.application.ImportUsersResponse;
import lms.identity.user.application.ImportUsersResponse.ImportRowResult;
import lms.identity.user.application.ImportUsersUseCase;
import lms.identity.user.application.UserResponse;
import lms.identity.user.application.UsersCsv;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Nhập/xuất người dùng: logic kiểm dòng, dryRun, mật khẩu tạm, chống CSV injection. Không cần Spring/DB. */
class ImportExportUsersTest {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID DEPT = UUID.randomUUID();

    UserRepository users = mock(UserRepository.class);
    AccountCredentialPort credentials = mock(AccountCredentialPort.class);
    DepartmentLookupPort departments = mock(DepartmentLookupPort.class);
    ImportUserRowExecutor executor = mock(ImportUserRowExecutor.class);
    ImportUsersUseCase useCase;

    @BeforeEach
    void setUp() {
        when(departments.activeIdsByCode()).thenReturn(Map.of("KHOA-CNTT", DEPT));
        when(credentials.findUserIdByUsername(anyString())).thenReturn(Optional.empty());
        useCase = new ImportUsersUseCase(users, credentials, departments, executor);
    }

    ImportUsersResponse run(boolean dryRun, Row... rows) {
        return TenantContext.callAs(TENANT, () -> useCase.execute(new ImportUsersRequest(dryRun, List.of(rows))));
    }

    static Row row(String username, String email, String dept, String password, Boolean reader) {
        return new Row(username, "Nguyễn Văn A", email, dept, password, reader);
    }

    @Test
    void dryRunChiKiemTraKhongGhi() {
        ImportUsersResponse r = run(true, row("alice", "a@x.vn", "khoa-cntt", null, null));

        assertEquals(1, r.total());
        assertEquals(0, r.created());
        assertEquals(0, r.failed());
        assertEquals(ImportRowResult.VALID, r.results().get(0).status());
        assertNull(r.results().get(0).temporaryPassword());
        verify(executor, never()).create(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    void taoDongHopLeSinhMatKhauTamKhiThieuVaBoQuaDongLoi() {
        ImportUsersResponse r = run(false,
                row("alice", "a@x.vn", "KHOA-CNTT", null, true),
                row("bob", "email-sai", null, null, null),
                row("carol", "c@x.vn", null, "MatKhauTam1", null));

        assertEquals(3, r.total());
        assertEquals(2, r.created());
        assertEquals(1, r.failed());
        ImportRowResult alice = r.results().get(0);
        assertEquals(ImportRowResult.CREATED, alice.status());
        assertNotNull(alice.temporaryPassword());
        assertTrue(alice.temporaryPassword().length() >= AccountCredentialPort.MIN_PASSWORD_LENGTH);
        assertEquals("INVALID_EMAIL", r.results().get(1).errorCode());
        // mật khẩu do người nhập cấp thì không trả lại
        assertNull(r.results().get(2).temporaryPassword());
        verify(executor).create(eq("alice"), anyString(), eq("a@x.vn"), eq(DEPT), anyString(), eq(true));
        verify(executor).create(eq("carol"), anyString(), eq("c@x.vn"), eq(null), eq("MatKhauTam1"), eq(false));
    }

    @Test
    void kiemTraTungLoaiLoi() {
        when(users.existsByUsername("trung")).thenReturn(true);
        ImportUsersResponse r = run(true,
                row("ab", "a@x.vn", null, null, null),
                row("phongban", "p@x.vn", "KHONG-CO", null, null),
                row("yeu", "y@x.vn", null, "123", null),
                row("trung", "t@x.vn", null, null, null),
                row("lap", "l@x.vn", null, null, null),
                row("lap", "l2@x.vn", null, null, null));

        List<String> codes = r.results().stream().map(ImportRowResult::errorCode).toList();
        assertEquals(List.of("INVALID_USERNAME", "DEPARTMENT_NOT_FOUND", "WEAK_PASSWORD", "DUPLICATE_USER"),
                codes.subList(0, 4));
        assertNull(codes.get(4));
        assertEquals("DUPLICATE_IN_FILE", codes.get(5));
        assertEquals(5, r.failed());
    }

    @Test
    void loiTrongTransactionCuaMotDongKhongAnhHuongDongKhac() {
        doThrow(DuplicateUserException.generic()).when(executor)
                .create(eq("dua"), anyString(), anyString(), any(), anyString(), anyBoolean());

        ImportUsersResponse r = run(false, row("dua", "d@x.vn", null, null, null), row("okay", "o@x.vn", null, null, null));

        assertEquals("DUPLICATE_USER", r.results().get(0).errorCode());
        assertEquals(ImportRowResult.CREATED, r.results().get(1).status());
        assertEquals(1, r.created());
        assertEquals(1, r.failed());
    }

    @Test
    void ketQuaVaYeuCauKhongInMatKhau() {
        Row row = row("alice", "a@x.vn", null, "BiMat12345", null);
        assertFalse(row.toString().contains("BiMat12345"));
        assertFalse(new ImportRowResult(1, "a", "CREATED", null, null, "BiMat12345").toString().contains("BiMat12345"));
    }

    @Test
    void csvChongCongThucVaBocNhayKep() {
        assertEquals("'=1+1", UsersCsv.cell("=1+1"));
        assertEquals("'+84", UsersCsv.cell("+84"));
        assertEquals("'-x", UsersCsv.cell("-x"));
        assertEquals("'@a", UsersCsv.cell("@a"));
        assertEquals("\"a,b\"", UsersCsv.cell("a,b"));
        assertEquals("\"a\"\"b\"", UsersCsv.cell("a\"b"));
        assertEquals("", UsersCsv.cell(null));

        UUID id = UUID.randomUUID();
        String csv = UsersCsv.render(
                List.of(new UserResponse(id, "alice", "=cmd", "a@x.vn", DEPT, UserStatus.ACTIVE)),
                Map.of(DEPT, "KHOA-CNTT"), Map.of(id, List.of("librarian", "reader")));
        assertEquals(UsersCsv.HEADER + "\r\nalice,'=cmd,a@x.vn,KHOA-CNTT,ACTIVE,librarian;reader\r\n", csv);
    }
}
