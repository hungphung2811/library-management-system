package lms.identity.contract.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mốc nhắc đã gửi; khóa (hợp đồng, mốc ngày, ends_at) chống gửi trùng. */
@Entity
@Table(name = "tenant_contract_reminders")
class ContractReminderJpaEntity {

    @Embeddable
    static class Key implements Serializable {

        @Column(name = "contract_id", nullable = false)
        private UUID contractId;

        @Column(name = "days_before", nullable = false)
        private int daysBefore;

        @Column(name = "ends_at", nullable = false)
        private Instant endsAt;

        protected Key() {
        }

        Key(UUID contractId, int daysBefore, Instant endsAt) {
            this.contractId = contractId;
            this.daysBefore = daysBefore;
            this.endsAt = endsAt;
        }

        int daysBefore() {
            return daysBefore;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && daysBefore == k.daysBefore && Objects.equals(contractId, k.contractId)
                    && Objects.equals(endsAt, k.endsAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(contractId, daysBefore, endsAt);
        }
    }

    @EmbeddedId
    private Key key;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(nullable = false)
    private boolean skipped;

    protected ContractReminderJpaEntity() {
    }

    ContractReminderJpaEntity(UUID contractId, int daysBefore, Instant endsAt, Instant sentAt, int recipientCount,
                              boolean skipped) {
        this.key = new Key(contractId, daysBefore, endsAt);
        this.sentAt = sentAt;
        this.recipientCount = recipientCount;
        this.skipped = skipped;
    }

    Key getKey() { return key; }
}
