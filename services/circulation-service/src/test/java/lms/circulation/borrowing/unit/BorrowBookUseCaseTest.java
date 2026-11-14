package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowBookCommand;
import lms.circulation.borrowing.application.PublishBorrowingEventPort;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.domain.BorrowingLimitExceededException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.BorrowingDomainService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BorrowBookUseCaseTest {

    private final BorrowingRepository repository = mock(BorrowingRepository.class);
    private final SaveBorrowingPort savePort = mock(SaveBorrowingPort.class);
    private final PublishBorrowingEventPort eventPort = mock(PublishBorrowingEventPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final BorrowBookUseCase useCase =
            new BorrowBookUseCase(new BorrowingDomainService(), repository, savePort, eventPort, clock);

    @Test
    void muonThanhCongLuuVaPublishEvent() {
        UUID userId = UUID.randomUUID();
        when(repository.countActiveItemsByUserId(userId)).thenReturn(0L);
        when(savePort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BorrowingResponse response = useCase.execute(new BorrowBookCommand(userId, List.of(UUID.randomUUID())));

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(savePort).save(any(BorrowingRecord.class));
        verify(eventPort, times(1)).publish(any());
    }

    @Test
    void vuotGioiHanThiKhongLuuVaKhongPublish() {
        UUID userId = UUID.randomUUID();
        when(repository.countActiveItemsByUserId(userId)).thenReturn(10L);

        assertThatThrownBy(() -> useCase.execute(new BorrowBookCommand(userId, List.of(UUID.randomUUID()))))
                .isInstanceOf(BorrowingLimitExceededException.class);

        verify(savePort, never()).save(any());
        verify(eventPort, never()).publish(any());
    }
}
