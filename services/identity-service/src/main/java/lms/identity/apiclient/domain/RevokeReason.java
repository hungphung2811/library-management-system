package lms.identity.apiclient.domain;

/** Lý do thu hồi credential (khớp CHECK của application_credentials.revoke_reason). */
public enum RevokeReason {
    ROTATED, MANUAL, APPLICATION_DELETED, COMPROMISED
}
