package lms.database.autoconfigure;

import java.util.Locale;

/** Quy ước tên bean: ORDER_DB -> orderDbDataSource, orderDbEntityManagerFactory, orderDbTransactionManager. */
public final class BeanNames {

    private BeanNames() {
    }

    public static String dataSource(String connectionName) {
        return base(connectionName) + "DataSource";
    }

    public static String entityManagerFactory(String connectionName) {
        return base(connectionName) + "EntityManagerFactory";
    }

    public static String transactionManager(String connectionName) {
        return base(connectionName) + "TransactionManager";
    }

    static String base(String connectionName) {
        String[] parts = connectionName.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            result.append(result.length() == 0 ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return result.toString();
    }
}
