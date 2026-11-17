package lms.identity.contract.domain;

import java.util.List;
import java.util.UUID;

/** Chỉ thêm. */
public interface ContractHistoryRepository {

    void append(ContractHistoryEntry entry);

    /** Mới nhất trước. */
    List<ContractHistoryEntry> findByContract(UUID contractId);
}
