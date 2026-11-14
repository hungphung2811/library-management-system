package lms.identity.user.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Hồ sơ độc giả. QĐ2: chỉ mượn/đặt trước khi thẻ còn hạn và tài khoản không bị khóa. */
public class ReaderProfile {

    private final UUID userId;
    private final String cardNo;
    private final String fullName;
    private final String email;
    private final ReaderType readerType;
    private LocalDate cardExpiryDate;
    private ReaderStatus status;

    private ReaderProfile(UUID userId, String cardNo, String fullName, String email, ReaderType readerType,
                          LocalDate cardExpiryDate, ReaderStatus status) {
        if (userId == null) throw new InvalidReaderException("userId không được null");
        if (cardNo == null || cardNo.isBlank()) throw new InvalidReaderException("Số thẻ không được để trống");
        if (fullName == null || fullName.isBlank()) throw new InvalidReaderException("Họ tên không được để trống");
        if (email == null || email.isBlank()) throw new InvalidReaderException("Email không được để trống");
        if (readerType == null) throw new InvalidReaderException("Loại độc giả không được null");
        if (cardExpiryDate == null) throw new InvalidReaderException("Hạn thẻ không được null");
        if (status == null) throw new InvalidReaderException("Trạng thái không được null");
        this.userId = userId;
        this.cardNo = cardNo.trim();
        this.fullName = fullName.trim();
        this.email = email.trim();
        this.readerType = readerType;
        this.cardExpiryDate = cardExpiryDate;
        this.status = status;
    }

    /** Cấp thẻ mới: hạn thẻ phải sau ngày cấp. */
    public static ReaderProfile issue(UUID userId, String cardNo, String fullName, String email,
                                      ReaderType readerType, LocalDate cardExpiryDate, LocalDate today) {
        if (cardExpiryDate != null && !cardExpiryDate.isAfter(today)) {
            throw new InvalidReaderException("Hạn thẻ phải sau ngày cấp");
        }
        return new ReaderProfile(userId, cardNo, fullName, email, readerType, cardExpiryDate, ReaderStatus.ACTIVE);
    }

    public static ReaderProfile restore(UUID userId, String cardNo, String fullName, String email,
                                        ReaderType readerType, LocalDate cardExpiryDate, ReaderStatus status) {
        return new ReaderProfile(userId, cardNo, fullName, email, readerType, cardExpiryDate, status);
    }

    /** QĐ2: thẻ còn hạn (tính hết ngày hạn) và tài khoản không bị khóa. */
    public boolean isCardValid(LocalDate today) {
        return status == ReaderStatus.ACTIVE && !cardExpiryDate.isBefore(today);
    }

    /** Gia hạn thẻ: hạn mới phải sau hôm nay và sau hạn hiện tại. */
    public void renewCard(LocalDate newExpiryDate, LocalDate today) {
        if (newExpiryDate == null || !newExpiryDate.isAfter(today)) {
            throw new InvalidReaderException("Hạn thẻ mới phải sau ngày hôm nay");
        }
        if (!newExpiryDate.isAfter(cardExpiryDate)) {
            throw new InvalidReaderException("Hạn thẻ mới phải sau hạn hiện tại (" + cardExpiryDate + ")");
        }
        this.cardExpiryDate = newExpiryDate;
    }

    public void lock() {
        this.status = ReaderStatus.LOCKED;
    }

    public void unlock() {
        this.status = ReaderStatus.ACTIVE;
    }

    public UUID userId() { return userId; }
    public String cardNo() { return cardNo; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public ReaderType readerType() { return readerType; }
    public LocalDate cardExpiryDate() { return cardExpiryDate; }
    public ReaderStatus status() { return status; }
}
