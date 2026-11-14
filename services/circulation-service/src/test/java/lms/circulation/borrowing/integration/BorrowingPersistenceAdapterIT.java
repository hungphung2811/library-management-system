package lms.circulation.borrowing.integration;

import static org.assertj.core.api.Assertions.assertThat;

import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.infrastructure.persistence.BorrowingPersistenceAdapter;
import lms.circulation.borrowing.infrastructure.config.PersistenceConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cần Docker. Surefire mặc định không chạy *IT; chạy bằng: mvn test -Dtest=BorrowingPersistenceAdapterIT. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PersistenceConfiguration.class)
class BorrowingPersistenceAdapterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    BorrowingPersistenceAdapter adapter;

    @Test
    void luuRoiDocLaiVaDemSoBanSaoDangMuon() {
        UUID userId = UUID.randomUUID();
        UUID copy1 = UUID.randomUUID();
        UUID copy2 = UUID.randomUUID();
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), LocalDate.of(2026, 1, 1));

        adapter.save(record);
        assertThat(adapter.countActiveItemsByUserId(userId)).isEqualTo(2);

        BorrowingRecord loaded = adapter.findById(record.id()).orElseThrow();
        loaded.returnCopy(copy1, LocalDate.of(2026, 1, 5));
        adapter.save(loaded);

        assertThat(adapter.countActiveItemsByUserId(userId)).isEqualTo(1);
    }
}
