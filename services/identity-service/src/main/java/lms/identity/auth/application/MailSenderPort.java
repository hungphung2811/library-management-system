package lms.identity.auth.application;

/** Gửi thư văn bản thuần. Lỗi gửi ném RuntimeException; người gọi quyết định nuốt hay không. */
public interface MailSenderPort {

    void send(String to, String subject, String body);
}
