package lms.support.notification.application;

/** Tên TransactionManager của AUDIT_DB (cùng database với context audit, do database-common đăng ký). */
public final class NotificationTransactions {

    public static final String MANAGER = "auditDbTransactionManager";

    private NotificationTransactions() {
    }
}
