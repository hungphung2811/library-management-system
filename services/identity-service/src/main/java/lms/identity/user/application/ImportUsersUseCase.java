package lms.identity.user.application;

import lms.identity.user.application.ImportUsersResponse.ImportRowResult;
import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nhập người dùng hàng loạt cho tenant hiện tại. KHÔNG có transaction bao ngoài: mỗi dòng hợp lệ tạo trong transaction
 * riêng ({@link ImportUserRowExecutor}), dòng lỗi không làm hỏng dòng khác. Kiểm từng dòng: tên đăng nhập, họ tên, email,
 * mật khẩu (độ dài, không trùng tên đăng nhập), phòng ban (theo mã, phải đang hoạt động), trùng trong file, trùng với
 * tài khoản đã có (ở BẤT KỲ tenant nào; thông điệp chung, không lộ tenant). dryRun=true chỉ kiểm tra.
 *
 * <p>Không có mật khẩu thì sinh ngẫu nhiên (SecureRandom) và trả MỘT lần trong response; mọi tài khoản tạo ra đều
 * mustChangePassword=true. Tuyệt đối không log dữ liệu dòng hay mật khẩu.
 */
public class ImportUsersUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImportUsersUseCase.class);

    static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,100}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final int MAX_PASSWORD_LENGTH = 72; // giới hạn bcrypt
    static final int MAX_CARD_NO_LENGTH = 32;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int GENERATED_LENGTH = 14;

    private final UserRepository users;
    private final AccountCredentialPort credentials;
    private final DepartmentLookupPort departments;
    private final ImportUserRowExecutor executor;
    private final SecureRandom random = new SecureRandom();

    public ImportUsersUseCase(UserRepository users, AccountCredentialPort credentials,
                              DepartmentLookupPort departments, ImportUserRowExecutor executor) {
        this.users = users;
        this.credentials = credentials;
        this.departments = departments;
        this.executor = executor;
    }

    public ImportUsersResponse execute(ImportUsersRequest request) {
        TenantContext.require();
        List<ImportUsersRequest.Row> rows = request.rows();
        if (rows == null || rows.isEmpty()) throw new InvalidUserException("Danh sách nhập không được để trống");
        if (rows.size() > ImportUsersRequest.MAX_ROWS) {
            throw new InvalidUserException("Tối đa " + ImportUsersRequest.MAX_ROWS + " dòng mỗi lần nhập");
        }
        Map<String, UUID> departmentIds = departments.activeIdsByCode();
        Set<String> seenUsernames = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();
        List<ImportRowResult> results = new ArrayList<>(rows.size());
        int created = 0;
        int failed = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImportUsersRequest.Row row = rows.get(i);
            String username = row == null || row.username() == null ? "" : row.username().trim().toLowerCase(Locale.ROOT);
            ImportRowResult result = process(i + 1, username, row, request.dryRun(), departmentIds, seenUsernames,
                    seenEmails);
            if (ImportRowResult.FAILED.equals(result.status())) failed++;
            if (ImportRowResult.CREATED.equals(result.status())) created++;
            results.add(result);
        }
        return new ImportUsersResponse(rows.size(), created, failed, results);
    }

    private ImportRowResult process(int number, String username, ImportUsersRequest.Row row, boolean dryRun,
                                    Map<String, UUID> departmentIds, Set<String> seenUsernames, Set<String> seenEmails) {
        if (row == null) return fail(number, username, "INVALID_ROW", "Dòng trống");
        String fullName = row.fullName() == null ? "" : row.fullName().trim();
        String email = row.email() == null ? "" : row.email().trim().toLowerCase(Locale.ROOT);
        boolean reader = Boolean.TRUE.equals(row.reader());

        if (!USERNAME.matcher(username).matches()) {
            return fail(number, username, "INVALID_USERNAME",
                    "Tên đăng nhập gồm chữ thường, số, dấu chấm, gạch dưới, gạch ngang, dài 3-100 ký tự");
        }
        if (fullName.isEmpty() || fullName.length() > 255) {
            return fail(number, username, "INVALID_FULL_NAME", "Họ tên không được để trống và tối đa 255 ký tự");
        }
        if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
            return fail(number, username, "INVALID_EMAIL", "Email không hợp lệ");
        }
        UUID departmentId = null;
        String code = row.departmentCode() == null ? "" : row.departmentCode().trim();
        if (!code.isEmpty()) {
            departmentId = departmentIds.get(code.toUpperCase(Locale.ROOT));
            if (departmentId == null) {
                return fail(number, username, "DEPARTMENT_NOT_FOUND", "Phòng ban không tồn tại hoặc đã ngừng hoạt động");
            }
        }
        String supplied = row.temporaryPassword();
        boolean generated = supplied == null || supplied.isEmpty();
        if (!generated && (supplied.length() < AccountCredentialPort.MIN_PASSWORD_LENGTH
                || supplied.length() > MAX_PASSWORD_LENGTH || supplied.equalsIgnoreCase(username))) {
            return fail(number, username, "WEAK_PASSWORD", "Mật khẩu tạm phải dài "
                    + AccountCredentialPort.MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH
                    + " ký tự và không trùng tên đăng nhập");
        }
        if (reader && username.length() > MAX_CARD_NO_LENGTH) {
            return fail(number, username, "INVALID_USERNAME",
                    "Độc giả dùng tên đăng nhập làm số thẻ nên tối đa " + MAX_CARD_NO_LENGTH + " ký tự");
        }
        boolean newUsername = seenUsernames.add(username);
        boolean newEmail = seenEmails.add(email);
        if (!newUsername || !newEmail) {
            return fail(number, username, "DUPLICATE_IN_FILE", "Tên đăng nhập hoặc email bị trùng trong file");
        }
        if (users.existsByUsername(username) || credentials.findUserIdByUsername(username).isPresent()
                || users.existsByEmail(email, null)) {
            return fail(number, username, "DUPLICATE_USER", DuplicateUserException.generic().getMessage());
        }
        if (dryRun) return new ImportRowResult(number, username, ImportRowResult.VALID, null, null, null);

        String password = generated ? generatePassword() : supplied;
        try {
            executor.create(username, fullName, email, departmentId, password, reader);
        } catch (DuplicateUserException e) {
            return fail(number, username, "DUPLICATE_USER", e.getMessage());
        } catch (DuplicateReaderException e) {
            return fail(number, username, "DUPLICATE_READER", e.getMessage());
        } catch (InvalidUserException | InvalidReaderException e) {
            return fail(number, username, "INVALID_ROW", e.getMessage());
        } catch (RuntimeException e) {
            // Không đưa dữ liệu dòng vào log; chỉ loại lỗi
            log.error("Nhập người dùng: lỗi không xác định ở dòng {} ({})", number, e.getClass().getSimpleName());
            return fail(number, username, "INTERNAL_ERROR", "Không tạo được người dùng do lỗi hệ thống");
        }
        return new ImportRowResult(number, username, ImportRowResult.CREATED, null, null, generated ? password : null);
    }

    private static ImportRowResult fail(int number, String username, String code, String message) {
        return new ImportRowResult(number, username, ImportRowResult.FAILED, code, message, null);
    }

    /** Mật khẩu tạm ngẫu nhiên, luôn có chữ hoa, chữ thường và số. */
    String generatePassword() {
        while (true) {
            StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
            for (int i = 0; i < GENERATED_LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String p = sb.toString();
            if (p.chars().anyMatch(Character::isUpperCase) && p.chars().anyMatch(Character::isLowerCase)
                    && p.chars().anyMatch(Character::isDigit)) {
                return p;
            }
        }
    }
}
