package lms.database.autoconfigure;

import lms.database.api.RelationalConnection;
import lms.database.core.DatabaseConnectionRegistry;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/** FactoryBean đưa DataSource / EntityManagerFactory / TransactionManager của một connection thành bean có tên. */
public final class RelationalConnectionFactoryBeans {

    private RelationalConnectionFactoryBeans() {
    }

    abstract static class Base<T> implements FactoryBean<T> {

        private final DatabaseConnectionRegistry registry;
        private final String connectionName;

        Base(DatabaseConnectionRegistry registry, String connectionName) {
            this.registry = registry;
            this.connectionName = connectionName;
        }

        RelationalConnection connection() {
            return registry.require(connectionName, RelationalConnection.class);
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }

    public static class DataSourceBean extends Base<DataSource> {
        public DataSourceBean(DatabaseConnectionRegistry registry, String connectionName) {
            super(registry, connectionName);
        }

        @Override
        public DataSource getObject() {
            return connection().dataSource();
        }

        @Override
        public Class<?> getObjectType() {
            return DataSource.class;
        }
    }

    public static class EntityManagerFactoryBean extends Base<EntityManagerFactory> {
        public EntityManagerFactoryBean(DatabaseConnectionRegistry registry, String connectionName) {
            super(registry, connectionName);
        }

        @Override
        public EntityManagerFactory getObject() {
            return connection().entityManagerFactory().orElseThrow(
                    () -> new IllegalStateException("Connection không cấu hình entity-packages nên không có EntityManagerFactory"));
        }

        @Override
        public Class<?> getObjectType() {
            return EntityManagerFactory.class;
        }
    }

    public static class TransactionManagerBean extends Base<PlatformTransactionManager> {
        public TransactionManagerBean(DatabaseConnectionRegistry registry, String connectionName) {
            super(registry, connectionName);
        }

        @Override
        public PlatformTransactionManager getObject() {
            return connection().transactionManager();
        }

        @Override
        public Class<?> getObjectType() {
            return PlatformTransactionManager.class;
        }
    }
}
