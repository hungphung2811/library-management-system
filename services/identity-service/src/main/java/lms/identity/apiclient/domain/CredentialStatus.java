package lms.identity.apiclient.domain;

/** Trạng thái credential. Hết hạn (expires_at) được suy ra, không có trạng thái riêng. */
public enum CredentialStatus {
    ACTIVE, REVOKED
}
