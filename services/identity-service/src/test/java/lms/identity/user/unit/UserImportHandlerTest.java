package lms.identity.user.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.ParsedUserRow;
import lms.identity.user.application.TemporaryPasswordGenerator;
import lms.identity.user.application.UserImportAuditPort;
import lms.identity.user.application.UserImportHandler;
import lms.identity.user.application.UserImportRowWriter;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UsersImportedEvent;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Kiểm tra (preview) và ghi (confirm) nhập người dùng từ Excel: luật domain, trùng theo lô, mật khẩu tự sinh, audit. */
class UserImportHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT = UUID.randomUUID();
    private static final ImportContext CTX = new ImportContext(TENANT, "admin-1", UUID.randomUUID(), Set.of(), false);

    private UserRepository users;
    private AccountCredentialPort credentials;
    private ReaderProfileRepository readers;
    private UserImportRowWriter writer;
    private UserImportAuditPort audit;
    private UserImportHandler handler;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        credentials = mock(AccountCredentialPort.class);
        readers = mock(ReaderProfileRepository.class);
        writer = mock(UserImportRowWriter.class);
        audit = mock(UserImportAuditPort.class);
        when(users.existingUsernames(anyCollection())).thenReturn(Set.of());
        when(users.existingEmails(anyCollection())).thenReturn(Set.of());
        when(credentials.existingUsernames(anyCollection())).thenReturn(Set.of());
        when(readers.existingCardNos(anyCollection())).thenReturn(Set.of());
        handler = new UserImportHandler(users, credentials, readers, writer, new TemporaryPasswordGenerator(), audit, CLOCK);
    }

    private static ImportRow row(int n, String... kv) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) values.put(kv[i], kv[i + 1]);
        return ImportRow.of(UserImportHandler.SHEET, n, values);
    }

    private static ImportRow user(int n, String username) {
        return row(n, "username", username, "fullName", "Nguyễn Văn " + n, "email", username + "@lib.vn");
    }

    private ImportRow validateOne(ImportRow r) {
        List<ImportRow> out = handler.validate(CTX, List.of(r));
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    // ------------------------------------------------------------------ preview

    @Test
    void validRowAndReaderRowAreValid() {
        List<ImportRow> out = handler.validate(CTX, List.of(user(2, "nva"),
                row(3, "username", "docgia01", "fullName", "Trần B", "email", "b@lib.vn", "isReader", "true",
                        "readerType", "LECTURER", "faculty", "CNTT", "dateOfBirth", "2000-05-01", "gender", "FEMALE")));
        assertThat(out).extracting(ImportRow::status).containsExactly(RowStatus.VALID, RowStatus.VALID);
    }

    @Test
    void invalidFieldsAreReportedOnTheRightColumn() {
        assertInvalid(row(2, "username", "AB", "fullName", "X", "email", "a@b.co"), "username");
        assertInvalid(row(2, "username", "abc", "fullName", "X", "email", "khong-phai-email"), "email");
        assertInvalid(row(2, "username", "abc", "fullName", "X", "email", "a@b.co", "phone", "abc-xyz"), "phone");
        assertInvalid(row(2, "username", "abc", "fullName", "X", "email", "a@b.co", "dateOfBirth", "2099-01-01"), "dateOfBirth");
        assertInvalid(row(2, "username", "a".repeat(40), "fullName", "X", "email", "a@b.co", "isReader", "true"), "username");
        assertInvalid(row(2, "username", "abc", "fullName", "X", "email", "a@b.co", "isReader", "true",
                "cardExpiryDate", "2020-01-01"), "cardExpiryDate");
        assertInvalid(row(2, "username", "abc", "fullName", "X", "email", "a@b.co", "isReader", "true",
                "cardExpiryDate", "2027-01-01", "cardIssuedDate", "2028-01-01"), "cardIssuedDate");
    }

    private void assertInvalid(ImportRow r, String column) {
        ImportRow out = validateOne(r);
        assertThat(out.status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.issues()).extracting(i -> i.column()).containsExactly(column);
    }

    @Test
    void duplicateInsideTheFileIsInvalid() {
        List<ImportRow> out = handler.validate(CTX, List.of(user(2, "nva"),
                row(3, "username", "NVA", "fullName", "Khác", "email", "khac@lib.vn"),
                row(4, "username", "other", "fullName", "Khác", "email", "NVA@lib.vn")));
        assertThat(out.get(0).status()).isEqualTo(RowStatus.VALID);
        assertThat(out.get(1).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(1).issues().get(0).code()).isEqualTo("DUPLICATE_IN_FILE");
        assertThat(out.get(2).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(2).issues().get(0).column()).isEqualTo("email");
    }

    @Test
    void existingUsernameEmailAndCredentialAreDuplicateWithGenericMessage() {
        when(users.existingUsernames(anyCollection())).thenReturn(Set.of("co-san"));
        when(credentials.existingUsernames(anyCollection())).thenReturn(Set.of("chi-credential"));
        when(users.existingEmails(anyCollection())).thenReturn(Set.of("trung@lib.vn"));
        List<ImportRow> out = handler.validate(CTX, List.of(user(2, "co-san"), user(3, "chi-credential"),
                row(4, "username", "moi", "fullName", "M", "email", "Trung@Lib.vn"), user(5, "hoanmoi")));
        assertThat(out).extracting(ImportRow::status).containsExactly(RowStatus.DUPLICATE, RowStatus.DUPLICATE,
                RowStatus.DUPLICATE, RowStatus.VALID);
        assertThat(out.get(0).issues().get(0).message()).isEqualTo(DuplicateUserException.GENERIC_MESSAGE);
    }

    @Test
    void existingCardNumberOfAReaderIsDuplicate() {
        when(readers.existingCardNos(anyCollection())).thenReturn(Set.of("docgia01"));
        ImportRow out = validateOne(row(2, "username", "docgia01", "fullName", "X", "email", "x@lib.vn", "isReader", "true"));
        assertThat(out.status()).isEqualTo(RowStatus.DUPLICATE);
        assertThat(out.issues().get(0).code()).isEqualTo("CARD_ALREADY_EXISTS");
    }

    @Test
    void readerColumnsOnANonReaderRowOnlyWarn() {
        ImportRow out = validateOne(row(2, "username", "nva", "fullName", "X", "email", "x@lib.vn", "faculty", "CNTT"));
        assertThat(out.status()).isEqualTo(RowStatus.VALID);
        assertThat(out.issues()).hasSize(1);
        assertThat(out.issues().get(0).severity().name()).isEqualTo("WARNING");
    }

    @Test
    void invalidRowsFromTheSyntaxCheckAreKeptAsIs() {
        ImportRow bad = row(2, "username", "nva").invalid(lms.imports.core.RowIssue.error("fullName", "REQUIRED", "Bắt buộc"));
        ImportRow out = validateOne(bad);
        assertThat(out).isEqualTo(bad);
    }

    @Test
    void validationUsesOneBatchQueryPerKeyKindRegardlessOfRowCount() {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            rows.add(i % 2 == 0 ? user(i + 2, "usr" + i)
                    : row(i + 2, "username", "rdr" + i, "fullName", "R", "email", "rdr" + i + "@lib.vn", "isReader", "true"));
        }
        List<ImportRow> out = handler.validate(CTX, rows);
        assertThat(out).hasSize(1_500).allMatch(r -> r.status() == RowStatus.VALID);
        verify(users, times(1)).existingUsernames(anyCollection());
        verify(users, times(1)).existingEmails(anyCollection());
        verify(credentials, times(1)).existingUsernames(anyCollection());
        verify(readers, times(1)).existingCardNos(anyCollection());
        // Không có truy vấn từng dòng
        verify(users, never()).existsByUsername(anyString());
        verify(users, never()).existsByEmail(anyString(), any());
        verify(credentials, never()).findUserIdByUsername(anyString());
        verify(readers, never()).existsByCardNo(anyString());
    }

    // ------------------------------------------------------------------ ghi

    @Test
    void commitCreatesRowsWithGeneratedPasswordsAndReturnsThemOnlyAsCredentials() {
        List<ImportRow> chunk = List.of(user(2, "nva"),
                row(3, "username", "docgia01", "fullName", "Trần B", "email", "b@lib.vn", "isReader", "true"));
        ChunkResult result = handler.commitChunk(CTX, chunk);

        assertThat(result.outcomes()).extracting(RowOutcome::status).containsOnly(OutcomeStatus.CREATED);
        assertThat(result.credentials()).hasSize(2);
        ArgumentCaptor<ParsedUserRow> rows = ArgumentCaptor.forClass(ParsedUserRow.class);
        ArgumentCaptor<String> passwords = ArgumentCaptor.forClass(String.class);
        verify(writer, times(2)).create(rows.capture(), passwords.capture());
        assertThat(passwords.getAllValues()).doesNotHaveDuplicates().allSatisfy(p -> {
            assertThat(p).hasSize(14);
            assertThat(p.chars().anyMatch(Character::isUpperCase)).isTrue();
            assertThat(p.chars().anyMatch(Character::isLowerCase)).isTrue();
            assertThat(p.chars().anyMatch(Character::isDigit)).isTrue();
        });
        assertThat(result.credentials()).extracting(c -> c.login()).containsExactly("nva", "docgia01");
        assertThat(result.credentials()).extracting(c -> c.secret()).containsExactlyElementsOf(passwords.getAllValues());
        // Mật khẩu không nằm trong kết quả từng dòng
        assertThat(result.outcomes().toString()).doesNotContain(passwords.getAllValues().get(0));

        ParsedUserRow reader = rows.getAllValues().get(1);
        assertThat(reader.reader()).isTrue();
        assertThat(reader.readerType()).isEqualTo(ReaderType.STUDENT);
        assertThat(reader.cardExpiryDate()).isEqualTo(LocalDate.of(2027, 3, 10));
        assertThat(rows.getAllValues().get(0).reader()).isFalse();
    }

    @Test
    void commitRechecksDuplicatesInOneBatchAndSkipsThem() {
        when(users.existingUsernames(anyCollection())).thenReturn(Set.of("nva"));
        ChunkResult result = handler.commitChunk(CTX, List.of(user(2, "nva"), user(3, "moi")));
        assertThat(result.outcomes()).extracting(RowOutcome::status)
                .containsExactly(OutcomeStatus.SKIPPED_DUPLICATE, OutcomeStatus.CREATED);
        assertThat(result.credentials()).extracting(c -> c.login()).containsExactly("moi");
        verify(users, times(1)).existingUsernames(anyCollection());
        verify(writer, times(1)).create(any(), anyString());
    }

    @Test
    void commitMapsRaceAndUnexpectedErrorsPerRow() {
        doThrow(DuplicateUserException.generic()).when(writer).create(
                org.mockito.ArgumentMatchers.argThat(p -> p != null && p.username().equals("dua")), anyString());
        doThrow(new IllegalStateException("chi tiết nội bộ nva-secret")).when(writer).create(
                org.mockito.ArgumentMatchers.argThat(p -> p != null && p.username().equals("loi")), anyString());
        ChunkResult result = handler.commitChunk(CTX, List.of(user(2, "dua"), user(3, "loi"), user(4, "okk")));
        assertThat(result.outcomes()).extracting(RowOutcome::status).containsExactly(OutcomeStatus.SKIPPED_DUPLICATE,
                OutcomeStatus.FAILED, OutcomeStatus.CREATED);
        assertThat(result.outcomes().get(1).issues().get(0).message()).doesNotContain("secret");
        assertThat(result.credentials()).extracting(c -> c.login()).containsExactly("okk");
    }

    @Test
    void completionWritesOneSummaryAuditWithoutPersonalData() {
        UUID importId = UUID.randomUUID();
        handler.onCompleted(CTX, new ImportResult(importId, "users", 7, 2, 1, true));
        ArgumentCaptor<UsersImportedEvent> captor = ArgumentCaptor.forClass(UsersImportedEvent.class);
        verify(audit, times(1)).record(captor.capture());
        UsersImportedEvent e = captor.getValue();
        assertThat(e.importId()).isEqualTo(importId);
        assertThat(e.actorUserId()).isEqualTo("admin-1");
        assertThat(e.created()).isEqualTo(7);
        assertThat(e.skippedDuplicate()).isEqualTo(2);
        assertThat(e.failed()).isEqualTo(1);
        assertThat(e.completed()).isTrue();
        assertThat(e.aggregateId()).isEqualTo(importId.toString());
    }

    @Test
    void schemaDeclaresNoPasswordRoleOrDepartmentColumn() {
        var keys = handler.schema().sheets().get(0).columns().stream().map(c -> c.key()).toList();
        assertThat(keys).contains("username", "fullName", "email", "isReader", "readerType")
                .noneMatch(k -> k.toLowerCase().contains("password") || k.toLowerCase().contains("role")
                        || k.toLowerCase().contains("department"));
        assertThat(handler.type()).isEqualTo("users");
    }
}
