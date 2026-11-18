package lms.identity.auth.infrastructure.messaging;

import lms.identity.auth.application.MailSenderPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Gửi thư qua SMTP (spring.mail.*, mặc định localhost:1025 là Mailpit của docker-compose). Không có JavaMailSender (chưa
 * cấu hình spring.mail.host) thì ném IllegalStateException; người gọi (EmailVerificationUseCase) chỉ log tên lỗi.
 */
public class SmtpMailSender implements MailSenderPort {

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public SmtpMailSender(ObjectProvider<JavaMailSender> sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) throw new IllegalStateException("Chưa cấu hình spring.mail.host");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        s.send(message);
    }
}
