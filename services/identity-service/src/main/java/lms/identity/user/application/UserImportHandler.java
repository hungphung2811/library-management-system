package lms.identity.user.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserPersonalInfo;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UsersImportedEvent;
import lms.imports.core.ChunkResult;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ColumnType;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssueCodes;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nhập người dùng (+ độc giả) từ Excel, loại {@code users} (ADR-0024 mục 3.4.4). Kiểm tra dùng lại chính các luật domain
 * ({@link User}, {@link UserPersonalInfo}, {@link ReaderPersonalInfo}, {@link ReaderProfile}), việc trùng tên đăng nhập /
 * email / số thẻ kiểm THEO LÔ (mỗi loại khóa một truy vấn {@code IN}, tối đa 1.000 phần tử mỗi câu) nên số câu SQL không
 * tăng theo số dòng ở bước preview. Ghi dòng đi qua {@link UserImportRowWriter} (một transaction mỗi dòng, dùng lại
 * CreateUserUseCase và IssueReaderCardUseCase).
 *
 * <p>Mật khẩu ban đầu tự sinh (SecureRandom), mọi tài khoản {@code mustChangePassword}; mật khẩu chỉ đi ra qua
 * {@link ChunkResult#credentials()} (file tải một lần), tuyệt đối không log, không audit, không nằm trong dòng preview.
 * Không gán role, không có phòng ban. Trùng tên đăng nhập (mọi tenant) hoặc email là DUPLICATE với thông điệp chung
 * (không lộ tenant). Dữ liệu dòng không bao giờ vào log.
 */
public class UserImportHandler implements ImportHandler {

    public static final String TYPE = "users";
    public static final String SHEET = "NguoiDung";
    static final int MAX_CARD_NO_LENGTH = 32;
    static final int DEFAULT_CARD_VALID_YEARS = 1;

    private static final Logger log = LoggerFactory.getLogger(UserImportHandler.class);

    private static final ImportSchema SCHEMA = ImportSchema.single(TYPE, 1, SHEET, List.of(
            ColumnSpec.text("username", "Tên đăng nhập", true, 100)
                    .withExample("nguyenvana").withHint("Chữ thường, số, '.', '_', '-', 3-100 ký tự; độc giả tối đa 32 ký tự (dùng làm số thẻ)"),
            ColumnSpec.text("fullName", "Họ tên", true, 255).withExample("Nguyễn Văn A"),
            ColumnSpec.text("email", "Email", true, 255).withExample("a@example.com"),
            ColumnSpec.text("phone", "Số điện thoại", false, 20).withHint("Chỉ chữ số, '+', '-' và khoảng trắng"),
            ColumnSpec.of("dateOfBirth", "Ngày sinh", false, ColumnType.DATE).withHint("dd/MM/yyyy hoặc yyyy-MM-dd, không ở tương lai"),
            ColumnSpec.enumeration("gender", "Giới tính", false, List.of("MALE", "FEMALE", "OTHER")),
            ColumnSpec.text("address", "Địa chỉ", false, 500),
            ColumnSpec.text("jobTitle", "Chức danh", false, 100),
            ColumnSpec.text("employeeCode", "Mã nhân viên", false, 32),
            ColumnSpec.of("isReader", "Là độc giả", false, ColumnType.BOOLEAN).withHint("Có/Không, mặc định Không"),
            ColumnSpec.enumeration("readerType", "Loại độc giả", false, List.of("STUDENT", "LECTURER"))
                    .withHint("Chỉ dùng khi là độc giả, mặc định STUDENT"),
            ColumnSpec.of("cardExpiryDate", "Hạn thẻ", false, ColumnType.DATE).withHint("Mặc định hôm nay + 1 năm"),
            ColumnSpec.text("faculty", "Khoa/đơn vị", false, 150),
            ColumnSpec.text("className", "Lớp/ngành", false, 50),
            ColumnSpec.text("memberCode", "Mã sinh viên/cán bộ", false, 32),
            ColumnSpec.of("cardIssuedDate", "Ngày cấp thẻ", false, ColumnType.DATE).withHint("Không sau hạn thẻ")));

    private static final List<String> READER_ONLY_COLUMNS = List.of("readerType", "cardExpiryDate", "faculty", "className",
            "memberCode", "cardIssuedDate");

    private final UserRepository users;
    private final AccountCredentialPort credentials;
    private final ReaderProfileRepository readers;
    private final UserImportRowWriter writer;
    private final TemporaryPasswordGenerator passwords;
    private final UserImportAuditPort audit;
    private final Clock clock;

    public UserImportHandler(UserRepository users, AccountCredentialPort credentials, ReaderProfileRepository readers,
                             UserImportRowWriter writer, TemporaryPasswordGenerator passwords, UserImportAuditPort audit,
                             Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.readers = readers;
        this.writer = writer;
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ImportSchema schema() {
        return SCHEMA;
    }

    // ---------------------------------------------------------------- preview

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        LocalDate today = LocalDate.now(clock);
        ImportRow[] out = new ImportRow[rows.size()];
        ParsedUserRow[] parsed = new ParsedUserRow[rows.size()];
        Set<String> seenUsernames = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            out[i] = row;
            if (row.status() != RowStatus.VALID) continue;
            try {
                parsed[i] = parse(row, today);
            } catch (InvalidUserException | InvalidReaderException e) {
                out[i] = row.invalid(RowIssue.error(columnOf(row, today), codeOf(e), e.getMessage()));
                continue;
            }
            if (!seenUsernames.add(parsed[i].username())) {
                out[i] = row.invalid(RowIssue.error("username", IssueCodes.DUPLICATE_IN_FILE, "Tên đăng nhập bị trùng trong file"));
                parsed[i] = null;
            } else if (!seenEmails.add(parsed[i].email())) {
                out[i] = row.invalid(RowIssue.error("email", IssueCodes.DUPLICATE_IN_FILE, "Email bị trùng trong file"));
                parsed[i] = null;
            }
        }
        Existing existing = existingKeys(parsed);
        for (int i = 0; i < out.length; i++) {
            ParsedUserRow p = parsed[i];
            if (p == null) continue;
            ImportRow row = out[i];
            if (existing.isDuplicate(p)) {
                out[i] = row.duplicate(RowIssue.error("username", "ALREADY_EXISTS", DuplicateUserException.GENERIC_MESSAGE));
            } else if (existing.cardNos.contains(p.username()) && p.reader()) {
                out[i] = row.duplicate(RowIssue.error("username", "CARD_ALREADY_EXISTS", "Số thẻ độc giả đã tồn tại"));
            } else if (!p.reader()) {
                for (String col : READER_ONLY_COLUMNS) {
                    if (row.hasValue(col)) {
                        out[i] = row.withIssue(RowIssue.warning(col, "READER_COLUMN_IGNORED",
                                "Cột thông tin độc giả bị bỏ qua vì dòng không phải độc giả"));
                        break;
                    }
                }
            }
        }
        return List.of(out);
    }

    // ---------------------------------------------------------------- ghi

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        LocalDate today = LocalDate.now(clock);
        ParsedUserRow[] parsed = new ParsedUserRow[chunk.size()];
        RowOutcome[] outcomes = new RowOutcome[chunk.size()];
        for (int i = 0; i < chunk.size(); i++) {
            try {
                parsed[i] = parse(chunk.get(i), today);
            } catch (InvalidUserException | InvalidReaderException e) {
                outcomes[i] = RowOutcome.failed(chunk.get(i), RowIssue.error(null, codeOf(e), e.getMessage()));
            }
        }
        // Kiểm lại theo lô ngay trước khi ghi (người khác có thể vừa tạo cùng tên đăng nhập/email)
        Existing existing = existingKeys(parsed);
        List<IssuedCredential> issued = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            if (outcomes[i] != null) continue;
            ImportRow row = chunk.get(i);
            ParsedUserRow p = parsed[i];
            if (existing.isDuplicate(p) || (p.reader() && existing.cardNos.contains(p.username()))) {
                outcomes[i] = RowOutcome.skipped(row, RowIssue.error("username", "ALREADY_EXISTS", DuplicateUserException.GENERIC_MESSAGE));
                continue;
            }
            String password = passwords.generate();
            try {
                writer.create(p, password);
                outcomes[i] = RowOutcome.created(row);
                issued.add(new IssuedCredential(p.username(), password));
            } catch (DuplicateUserException | DuplicateReaderException e) {
                outcomes[i] = RowOutcome.skipped(row, RowIssue.error("username", "ALREADY_EXISTS", e.getMessage()));
            } catch (InvalidUserException | InvalidReaderException e) {
                outcomes[i] = RowOutcome.failed(row, RowIssue.error(null, codeOf(e), e.getMessage()));
            } catch (RuntimeException e) {
                // Chỉ log loại lỗi, không log dữ liệu dòng hay mật khẩu
                log.error("Nhập người dùng: lỗi không xác định ở dòng {} ({})", row.rowNumber(), e.getClass().getSimpleName());
                outcomes[i] = RowOutcome.failed(row, RowIssue.error(null, "INTERNAL_ERROR", "Không tạo được người dùng do lỗi hệ thống"));
            }
        }
        return new ChunkResult(List.of(outcomes), issued);
    }

    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        audit.record(new UsersImportedEvent(UUID.randomUUID(), result.importId(), ctx.userId(), result.created(),
                result.skippedDuplicate(), result.failed(), result.completed(), clock.instant()));
    }

    // ---------------------------------------------------------------- tiện ích

    private record Existing(Set<String> usernames, Set<String> emails, Set<String> cardNos) {
        boolean isDuplicate(ParsedUserRow p) {
            return usernames.contains(p.username()) || emails.contains(p.email());
        }
    }

    /** Một truy vấn IN cho mỗi loại khóa (tên đăng nhập ở users và credentials, email, số thẻ), bất kể số dòng. */
    private Existing existingKeys(ParsedUserRow[] parsed) {
        Set<String> usernames = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Set<String> cardNos = new HashSet<>();
        for (ParsedUserRow p : parsed) {
            if (p == null) continue;
            usernames.add(p.username());
            emails.add(p.email());
            if (p.reader()) cardNos.add(p.username());
        }
        Set<String> foundUsernames = new HashSet<>();
        Set<String> foundEmails = new HashSet<>();
        Set<String> foundCards = new HashSet<>();
        if (!usernames.isEmpty()) {
            foundUsernames.addAll(users.existingUsernames(usernames));
            foundUsernames.addAll(credentials.existingUsernames(usernames));
            foundEmails.addAll(users.existingEmails(emails));
        }
        if (!cardNos.isEmpty()) foundCards.addAll(readers.existingCardNos(cardNos));
        return new Existing(foundUsernames, foundEmails, foundCards);
    }

    /** Dựng và kiểm dòng bằng luật domain; ném InvalidUserException/InvalidReaderException khi vi phạm. */
    ParsedUserRow parse(ImportRow row, LocalDate today) {
        String username = row.value("username").trim().toLowerCase(Locale.ROOT);
        String fullName = row.value("fullName").trim();
        String email = row.value("email").trim().toLowerCase(Locale.ROOT);
        User user = User.create(null, username, fullName, email);
        UserPersonalInfo personal = UserPersonalInfo.of(blankToNull(row.value("phone")), date(row, "dateOfBirth"),
                gender(row), blankToNull(row.value("address")), null, blankToNull(row.value("jobTitle")),
                blankToNull(row.value("employeeCode")), today);
        boolean reader = "true".equalsIgnoreCase(row.value("isReader"));
        if (!reader) {
            return new ParsedUserRow(user.username(), user.fullName(), user.email(), personal, false, null, null, null);
        }
        if (user.username().length() > MAX_CARD_NO_LENGTH) {
            throw new InvalidReaderException("Độc giả dùng tên đăng nhập làm số thẻ nên tối đa " + MAX_CARD_NO_LENGTH + " ký tự");
        }
        ReaderType type = row.hasValue("readerType") ? ReaderType.valueOf(row.value("readerType").toUpperCase(Locale.ROOT))
                : ReaderType.STUDENT;
        LocalDate expiry = row.hasValue("cardExpiryDate") ? date(row, "cardExpiryDate")
                : today.plusYears(DEFAULT_CARD_VALID_YEARS);
        ReaderPersonalInfo info = ReaderPersonalInfo.of(personal.phone(), personal.dateOfBirth(), personal.gender(),
                personal.address(), blankToNull(row.value("faculty")), blankToNull(row.value("className")),
                blankToNull(row.value("memberCode")), date(row, "cardIssuedDate"), today);
        // ReaderProfile.issue kiểm hạn thẻ sau hôm nay và ngày cấp thẻ không sau hạn thẻ
        ReaderProfile.issue(UUID.randomUUID(), user.username(), user.fullName(), user.email(), type, expiry, today, info);
        return new ParsedUserRow(user.username(), user.fullName(), user.email(), personal, true, type, expiry, info);
    }

    /** Xác định cột gây lỗi bằng cách thử từng nhóm trường riêng lẻ trên chính luật domain (không viết lại luật). */
    private String columnOf(ImportRow row, LocalDate today) {
        String username = row.value("username").trim();
        String fullName = row.value("fullName").trim();
        String email = row.value("email").trim();
        if (fails(() -> User.create(null, username, "Ten", "a@b.co"))) return "username";
        if (fails(() -> User.create(null, "abc", fullName, "a@b.co"))) return "fullName";
        if (fails(() -> User.create(null, "abc", "Ten", email))) return "email";
        String[] personalColumns = {"phone", "dateOfBirth", "address", "jobTitle", "employeeCode"};
        for (String col : personalColumns) {
            boolean bad = fails(() -> UserPersonalInfo.of(
                    "phone".equals(col) ? blankToNull(row.value("phone")) : null,
                    "dateOfBirth".equals(col) ? date(row, "dateOfBirth") : null, null,
                    "address".equals(col) ? blankToNull(row.value("address")) : null, null,
                    "jobTitle".equals(col) ? blankToNull(row.value("jobTitle")) : null,
                    "employeeCode".equals(col) ? blankToNull(row.value("employeeCode")) : null, today));
            if (bad) return col;
        }
        if ("true".equalsIgnoreCase(row.value("isReader"))) {
            if (username.length() > MAX_CARD_NO_LENGTH) return "username";
            LocalDate expiry = row.hasValue("cardExpiryDate") ? date(row, "cardExpiryDate")
                    : today.plusYears(DEFAULT_CARD_VALID_YEARS);
            if (expiry != null && !expiry.isAfter(today)) return "cardExpiryDate";
            LocalDate issued = date(row, "cardIssuedDate");
            if (issued != null && expiry != null && issued.isAfter(expiry)) return "cardIssuedDate";
            for (String col : List.of("faculty", "className", "memberCode")) {
                boolean bad = fails(() -> ReaderPersonalInfo.of(null, null, null, null,
                        "faculty".equals(col) ? blankToNull(row.value("faculty")) : null,
                        "className".equals(col) ? blankToNull(row.value("className")) : null,
                        "memberCode".equals(col) ? blankToNull(row.value("memberCode")) : null, null, today));
                if (bad) return col;
            }
        }
        return null;
    }

    private static boolean fails(Runnable check) {
        try {
            check.run();
            return false;
        } catch (InvalidUserException | InvalidReaderException e) {
            return true;
        }
    }

    private static String codeOf(RuntimeException e) {
        return e instanceof InvalidReaderException ? "INVALID_READER" : "INVALID_USER";
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static LocalDate date(ImportRow row, String key) {
        String v = row.value(key);
        return v.isEmpty() ? null : LocalDate.parse(v);
    }

    private static Gender gender(ImportRow row) {
        String v = row.value("gender");
        return v.isEmpty() ? null : Gender.valueOf(v.toUpperCase(Locale.ROOT));
    }
}
