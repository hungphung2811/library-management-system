package lms.support.notification.infrastructure.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import lms.support.notification.application.NotificationProvider;
import lms.support.notification.domain.AddressMasker;
import lms.support.notification.domain.NotificationChannel;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Gửi email văn bản thuần qua SMTP (spring.mail.*, mặc định Mailpit localhost:1025), theo mẫu SmtpMailSender của
 * identity. From = lms.notification.email.from, tên hiển thị là tên tenant. Không lưu/log địa chỉ đầy đủ và không đưa
 * thông điệp gốc của SMTP (có thể chứa địa chỉ) ra ngoài: chỉ mã lỗi SMTP_4XX, SMTP_5XX, TIMEOUT, SMTP_CONNECT...
 * SMTP chết không làm service DOWN (management.health.mail.enabled=false): lỗi chỉ thành lượt thử lại.
 */
public class EmailProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger("lms.log.outbound.smtp");

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public EmailProvider(ObjectProvider<JavaMailSender> sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public SendResult send(RenderedMessage message, ResolvedAddress address) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) throw new TransientSendException("MAIL_NOT_CONFIGURED", null);
        String masked = AddressMasker.maskEmail(address.address());
        try {
            MimeMessage mime = s.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(sender(address));
            helper.setTo(address.address());
            helper.setSubject(message.subject());
            helper.setText(message.body(), false);
            s.send(mime);
            log.info("SMTP gửi thư tới {} thành công", masked);
            return new SendResult(mime.getMessageID());
        } catch (MailParseException | MessagingException | UnsupportedEncodingException e) {
            // Địa chỉ/nội dung không dựng được thành thư: thử lại cũng vậy
            log.warn("SMTP từ chối dựng thư tới {}: {}", masked, e.getClass().getSimpleName());
            throw new PermanentSendException("SMTP_INVALID_MESSAGE", e);
        } catch (MailAuthenticationException e) {
            log.warn("SMTP xác thực lỗi khi gửi tới {}", masked);
            throw new TransientSendException("SMTP_AUTH", e);
        } catch (MailException e) {
            throw classify(e, masked);
        }
    }

    private InternetAddress sender(ResolvedAddress address) throws MessagingException, UnsupportedEncodingException {
        String name = address.senderName();
        return name == null || name.isBlank() ? new InternetAddress(from) : new InternetAddress(from, name, "UTF-8");
    }

    /** Phân loại theo mã trả lời SMTP: 5xx vĩnh viễn (địa chỉ sai, bị từ chối), còn lại (4xx, timeout, mất kết nối) tạm thời. */
    static RuntimeException classify(MailException e, String masked) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                log.warn("SMTP timeout khi gửi tới {}", masked);
                return new TransientSendException("TIMEOUT", e);
            }
            Integer code = null;
            if (t instanceof SMTPAddressFailedException a) code = a.getReturnCode();
            if (t instanceof SMTPSendFailedException f) code = f.getReturnCode();
            if (code != null) {
                if (code >= 500) {
                    log.warn("SMTP {} từ chối thư tới {}", code, masked);
                    return new PermanentSendException("SMTP_5XX", e);
                }
                log.warn("SMTP {} tạm hoãn thư tới {}", code, masked);
                return new TransientSendException("SMTP_4XX", e);
            }
        }
        log.warn("SMTP lỗi kết nối khi gửi tới {}: {}", masked, e.getClass().getSimpleName());
        return new TransientSendException("SMTP_CONNECT", e);
    }
}
