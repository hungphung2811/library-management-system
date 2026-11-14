package lms.circulation.reservation.infrastructure.messaging;

import lms.circulation.reservation.application.ExpireOverdueReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** UC14: bộ hẹn giờ chạy đầu mỗi ngày. Chạy trùng giữa nhiều instance vẫn an toàn vì use case chỉ chọn đặt trước đã quá hạn. */
@Component
public class ExpireReservationsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireReservationsJob.class);

    private final ExpireOverdueReservationsUseCase expire;

    public ExpireReservationsJob(ExpireOverdueReservationsUseCase expire) {
        this.expire = expire;
    }

    @Scheduled(cron = "${app.reservation.expire-cron:0 5 0 * * *}")
    public void run() {
        int processed = expire.execute();
        log.info("UC14: đã xử lý {} đặt trước quá hạn giữ", processed);
    }
}
