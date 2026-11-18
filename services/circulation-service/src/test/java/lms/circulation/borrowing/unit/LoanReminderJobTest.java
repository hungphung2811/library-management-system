package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.borrowing.application.SendLoanRemindersUseCase;
import lms.circulation.borrowing.infrastructure.messaging.LoanReminderJob;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

/** Job nhắc hạn: quét từng tenant ACTIVE trong đúng TenantContext, lịch 07:00 giờ Việt Nam, tắt được bằng cờ. */
class LoanReminderJobTest {

    private final SendLoanRemindersUseCase reminders = mock(SendLoanRemindersUseCase.class);

    @SuppressWarnings("unchecked")
    private LoanReminderJob job(List<UUID> tenants) {
        ActiveTenantsPort port = () -> tenants;
        ObjectProvider<ActiveTenantsPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return new LoanReminderJob(reminders, provider);
    }

    @Test
    void lichMacDinhBayGioSangGioVietNam() throws Exception {
        Scheduled s = LoanReminderJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(s.cron()).isEqualTo("${app.borrowing.reminder-cron:0 0 7 * * *}");
        assertThat(s.zone()).isEqualTo("${app.borrowing.reminder-zone:Asia/Ho_Chi_Minh}");
    }

    @Test
    void tatDuocBangCoApp() {
        ConditionalOnProperty c = LoanReminderJob.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(c.name()).containsExactly("app.borrowing.reminder-enabled");
        assertThat(c.havingValue()).isEqualTo("true");
        assertThat(c.matchIfMissing()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void chuaCoPortThiBoQuaKhongGoiUseCase() {
        ObjectProvider<ActiveTenantsPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        new LoanReminderJob(reminders, provider).run();
        verifyNoInteractions(reminders);
    }

    @Test
    void chayLanLuotTungTenantTrongDungNguCanhVaKhongRoNguCanh() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Optional<UUID>> seen = new ArrayList<>();
        doAnswer(i -> {
            seen.add(TenantContext.current());
            return new SendLoanRemindersUseCase.Summary(0, 0);
        }).when(reminders).execute(any());

        job(List.of(a, b)).run();

        assertThat(seen).containsExactly(Optional.of(a), Optional.of(b));
        assertThat(TenantContext.current()).isEmpty();
        verify(reminders).execute(a);
        verify(reminders).execute(b);
    }

    @Test
    void khongCoTenantNaoThiKhongChay() {
        job(List.of()).run();
        verify(reminders, never()).execute(any());
    }

    @Test
    void motTenantLoiKhongDungCacTenantKhac() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(reminders.execute(a)).thenThrow(new IllegalStateException("Kafka lỗi"));
        when(reminders.execute(b)).thenReturn(new SendLoanRemindersUseCase.Summary(1, 2));

        job(List.of(a, b)).run();

        verify(reminders).execute(a);
        verify(reminders).execute(b);
        assertThat(TenantContext.current()).isEmpty();
    }
}
