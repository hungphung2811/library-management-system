package lms.identity.contract.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** Cấu hình nghiệp vụ hợp đồng (lms.contract.*): múi giờ nghiệp vụ, ân hạn, các mốc nhắc hạn (ngày). */
public record ContractPolicy(ZoneId zone, Duration gracePeriod, List<Integer> reminderDays) {

    public ContractPolicy {
        reminderDays = List.copyOf(reminderDays);
    }
}
