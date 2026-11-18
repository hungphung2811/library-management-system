package lms.identity.apiclient.domain;

/** Hành động ghi vào application_history (khớp CHECK của bảng; TOKEN_DENIED_REPEATEDLY dành cho pha I2). */
public enum ApplicationHistoryAction {
    CREATED, UPDATED, SUSPENDED, REACTIVATED, DELETED, SCOPES_CHANGED, SITE_KEY_ROTATED, CREDENTIAL_CREATED,
    CREDENTIAL_REVOKED, TOKEN_DENIED_REPEATEDLY, DOMAIN_ADDED, DOMAIN_REMOVED
}
