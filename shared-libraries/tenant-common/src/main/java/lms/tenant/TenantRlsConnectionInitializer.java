package lms.tenant;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Lớp 2 của cách ly tenant: đặt biến phiên {@code app.tenant_id} của PostgreSQL theo {@link TenantContext} cho mọi
 * kết nối lấy từ {@link DataSource}, để chính sách RLS lọc dòng.
 *
 * <p>Cách gắn: bọc mọi bean {@link DataSource}. Mỗi lần mượn kết nối (Spring/Hibernate mượn một kết nối cho mỗi
 * transaction, trước câu lệnh đầu tiên) thì đặt {@code app.tenant_id} = tenant hiện tại, hoặc chuỗi rỗng nếu không có
 * tenant (fail-closed: policy so với NULL nên không thấy dòng nào). Khi trả kết nối về pool thì đặt lại chuỗi rỗng,
 * nên giá trị không rò sang lần mượn kế tiếp kể cả khi người mượn sau không đi qua lớp bọc này. Giới hạn: đổi tenant
 * giữa chừng một transaction đang giữ kết nối (runAs lồng bên trong) không cập nhật biến phiên.
 *
 * <p>Quy ước RLS cho mọi bảng thuộc tenant (migration của các task sau dùng đúng mẫu này):
 * <pre>
 * ALTER TABLE t ENABLE ROW LEVEL SECURITY;
 * ALTER TABLE t FORCE ROW LEVEL SECURITY;
 * CREATE POLICY tenant_isolation ON t
 *     USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 *     WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
 * </pre>
 * Chỉ tác dụng khi ứng dụng kết nối bằng vai trò không phải superuser và không có BYPASSRLS ({@code <svc>_app}).
 * Kết nối tới CSDL không phải PostgreSQL được bỏ qua.
 */
public class TenantRlsConnectionInitializer implements BeanPostProcessor {

    public static final String SETTING = "app.tenant_id";

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(TenantRlsConnectionInitializer.class);
    public static final String SCOPE_SETTING = "app.tenant_scope";
    /** Đặt cả hai biến trong cùng một câu lệnh để không bao giờ lệch nhau. */
    static final String SET_SQL = "SELECT set_config('" + SETTING + "', ?, false), set_config('" + SCOPE_SETTING
            + "', ?, false)";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TenantRlsDataSource)) {
            return wrap(dataSource);
        }
        return bean;
    }

    /** Bọc một DataSource dựng tay (không phải bean), ví dụ nguồn dữ liệu của database-common. */
    public static DataSource wrap(DataSource dataSource) {
        return dataSource instanceof TenantRlsDataSource ? dataSource : new TenantRlsDataSource(dataSource);
    }

    /**
     * Đặt {@code app.tenant_id} và {@code app.tenant_scope} theo {@link TenantContext}: ALL thì ('', 'all'), một tenant
     * thì (t, ''), không có thì ('', '').
     */
    static void apply(Connection connection) throws SQLException {
        if (TenantContext.isAllTenants()) {
            set(connection, "", "all");
        } else {
            set(connection, TenantContext.current().map(UUID::toString).orElse(""), "");
        }
    }

    /** Xóa {@code app.tenant_id} trước khi kết nối về pool. */
    static void reset(Connection connection) throws SQLException {
        // Việc dở (nếu có) vốn cũng bị pool rollback khi trả kết nối; rollback trước để lệnh xóa không hỏng theo
        // một transaction đã lỗi.
        if (!connection.isClosed() && !connection.getAutoCommit()) connection.rollback();
        set(connection, "", "");
    }

    private static void set(Connection connection, String tenant, String scope) throws SQLException {
        if (connection.isClosed() || !isPostgres(connection)) return;
        try (PreparedStatement statement = connection.prepareStatement(SET_SQL)) {
            statement.setString(1, tenant);
            statement.setString(2, scope);
            statement.execute();
        }
        // Kết nối vừa mượn/sắp trả không có việc dở; commit để giá trị phiên không bị rollback của transaction sau xóa.
        if (!connection.getAutoCommit()) connection.commit();
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
    }

    /** DataSource bọc: mượn kết nối thì đặt tenant, đóng kết nối thì xóa. */
    static final class TenantRlsDataSource implements DataSource {

        private final DataSource delegate;

        TenantRlsDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return prepare(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return prepare(delegate.getConnection(username, password));
        }

        private static Connection prepare(Connection connection) throws SQLException {
            try {
                apply(connection);
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
            return (Connection) Proxy.newProxyInstance(TenantRlsConnectionInitializer.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, new ResettingHandler(connection));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return iface.isInstance(this) ? (T) this : delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public String toString() {
            return "TenantRlsDataSource[" + delegate + "]";
        }
    }

    private record ResettingHandler(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "TenantRlsConnection[" + target + "]";
                case "close":
                    try {
                        reset(target);
                    } catch (SQLException | RuntimeException e) {
                        // Người mượn sau qua lớp bọc vẫn ghi đè giá trị; chỉ ghi nhận để không che lỗi đóng kết nối.
                        log.warn("Không xóa được {} trước khi trả kết nối về pool", SETTING, e);
                    }
                    break;
                default:
                    break;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
