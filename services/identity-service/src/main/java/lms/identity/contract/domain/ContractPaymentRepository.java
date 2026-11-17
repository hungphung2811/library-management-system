package lms.identity.contract.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Chỉ thêm: không có update/delete (và DB cũng chặn bằng trigger + REVOKE). */
public interface ContractPaymentRepository {

    ContractPayment append(ContractPayment payment);

    Optional<ContractPayment> findById(UUID id);

    /** Theo thứ tự ghi (cũ → mới). */
    List<ContractPayment> findByContract(UUID contractId);

    /** Tổng amount (kể cả dòng đảo) theo hợp đồng; hợp đồng chưa có dòng nào thì không có khóa. */
    Map<UUID, Long> paidByContracts(Collection<UUID> contractIds);

    boolean isReversed(UUID paymentId);
}
