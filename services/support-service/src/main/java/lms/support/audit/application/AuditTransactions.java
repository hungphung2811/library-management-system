package lms.support.audit.application;

/**
 * Tên TransactionManager của AUDIT_DB (bean do database-common đăng ký theo quy ước
 * AUDIT_DB -> auditDbTransactionManager). Gom về một chỗ để không rải chuỗi tên khắp nơi.
 */
public final class AuditTransactions {

    public static final String MANAGER = "auditDbTransactionManager";

    private AuditTransactions() {
    }
}
