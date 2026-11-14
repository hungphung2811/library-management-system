package lms.database.api;

import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Kết nối quan hệ (PostgreSQL, MySQL, SQL Server): DataSource + (tùy chọn) JPA + TransactionManager riêng. */
public interface RelationalConnection extends DatabaseConnection {

    DataSource dataSource();

    /** Chỉ có khi cấu hình entity-packages; nếu không, connection chỉ dùng JDBC. */
    Optional<EntityManagerFactory> entityManagerFactory();

    /** JpaTransactionManager nếu có EntityManagerFactory, ngược lại DataSourceTransactionManager. */
    PlatformTransactionManager transactionManager();
}
