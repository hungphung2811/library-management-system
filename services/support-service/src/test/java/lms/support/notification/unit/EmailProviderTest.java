package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.SocketTimeoutException;
import java.util.Properties;
import lms.support.notification.application.NotificationProvider.PermanentSendException;
import lms.support.notification.application.NotificationProvider.RenderedMessage;
import lms.support.notification.application.NotificationProvider.ResolvedAddress;
import lms.support.notification.application.NotificationProvider.TransientSendException;
import lms.support.notification.infrastructure.email.EmailProvider;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/** EmailProvider: dựng thư UTF-8, phân loại lỗi SMTP thành tạm thời/vĩnh viễn, không rò địa chỉ vào thông điệp lỗi. */
class EmailProviderTest {

    private final JavaMailSender mail = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private final EmailProvider email;
    private final ResolvedAddress to = new ResolvedAddress("nguyen.van.a@example.com", "Nguyễn Văn A", "Hiệu sách Mẫu");
    private final RenderedMessage msg = new RenderedMessage("Tiêu đề tiếng Việt", "Nội dung có dấu");

    EmailProviderTest() {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        email = new EmailProvider(provider, "no-reply@lms.local");
    }

    @Test
    void guiThanhCongDungNguoiNhanTieuDeVaTenNguoiGui() throws Exception {
        var result = email.send(msg, to);
        org.mockito.ArgumentCaptor<MimeMessage> cap = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(cap.capture());
        MimeMessage sent = cap.getValue();
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("nguyen.van.a@example.com");
        assertThat(sent.getSubject()).isEqualTo("Tiêu đề tiếng Việt");
        assertThat(sent.getFrom()[0].toString()).contains("no-reply@lms.local");
        assertThat(((jakarta.mail.internet.InternetAddress) sent.getFrom()[0]).getPersonal()).isEqualTo("Hiệu sách Mẫu");
        assertThat(sent.getContent().toString()).contains("Nội dung có dấu");
        assertThat(result).isNotNull();
    }

    @Test
    void chuaCauHinhMailThiLoiTamThoi() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(TransientSendException.class,
                e -> assertThat(e.code()).isEqualTo("MAIL_NOT_CONFIGURED"));
    }

    @Test
    void smtp5xxLaVinhVienSmtp4xxVaTimeoutLaTamThoi() {
        doThrow(new MailSendException("x", new SMTPAddressFailedException(null, "RCPT", 550, "no such user")))
                .when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(PermanentSendException.class,
                e -> assertThat(e.code()).isEqualTo("SMTP_5XX"));

        doThrow(new MailSendException("x", new SMTPSendFailedException("DATA", 451, "try later", null, null, null, null)))
                .when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(TransientSendException.class,
                e -> assertThat(e.code()).isEqualTo("SMTP_4XX"));

        doThrow(new MailSendException("x", new SocketTimeoutException("Read timed out")))
                .when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(TransientSendException.class,
                e -> assertThat(e.code()).isEqualTo("TIMEOUT"));

        doThrow(new MailSendException("Couldn't connect to host", new java.net.ConnectException("refused")))
                .when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(TransientSendException.class,
                e -> assertThat(e.code()).isEqualTo("SMTP_CONNECT"));

        doThrow(new MailAuthenticationException("auth")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> email.send(msg, to)).isInstanceOfSatisfying(TransientSendException.class,
                e -> assertThat(e.code()).isEqualTo("SMTP_AUTH"));
    }

    @Test
    void diaChiHongKhongDungDuocThuThiLoiVinhVienVaKhongLoDiaChi() {
        ResolvedAddress bad = new ResolvedAddress("khong co @ dau", "A", "B");
        assertThatThrownBy(() -> email.send(msg, bad)).isInstanceOfSatisfying(PermanentSendException.class,
                e -> {
                    assertThat(e.code()).isEqualTo("SMTP_INVALID_MESSAGE");
                    assertThat(e.getMessage()).doesNotContain("khong co");
                });
    }

    @Test
    void toStringCuaDiaChiKhongLoEmail() {
        assertThat(to.toString()).doesNotContain("nguyen.van.a");
    }
}
