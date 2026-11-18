package lms.identity.user.application;

import java.security.SecureRandom;

/**
 * Sinh mật khẩu tạm ngẫu nhiên (SecureRandom, 14 ký tự, bỏ ký tự dễ nhầm, luôn có chữ hoa, chữ thường và số). Dùng chung
 * cho nhập JSON cũ ({@link ImportUsersUseCase}) và nhập Excel ({@link UserImportHandler}). Không log giá trị sinh ra.
 */
public class TemporaryPasswordGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int GENERATED_LENGTH = 14;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
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
