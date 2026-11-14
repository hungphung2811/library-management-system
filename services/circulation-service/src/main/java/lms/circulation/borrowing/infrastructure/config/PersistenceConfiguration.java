package lms.circulation.borrowing.infrastructure.config;

import lms.circulation.borrowing.infrastructure.persistence.BorrowingPersistenceAdapter;
import lms.circulation.borrowing.infrastructure.persistence.SpringDataBorrowingRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Đăng ký adapter persistence. Tách riêng vì SpringDataBorrowingRepository là package-private. */
@Configuration
public class PersistenceConfiguration {

    @Bean
    BorrowingPersistenceAdapter borrowingPersistenceAdapter(SpringDataBorrowingRepository jpa) {
        return new BorrowingPersistenceAdapter(jpa);
    }
}
