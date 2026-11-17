package lms.api.reports.client;

import java.io.IOException;
import java.io.InputStream;
import lms.api.reports.dto.ReportHeaders;

/** Nơi nhận luồng CSV của service; chỉ hợp lệ trong lúc gọi (luồng đóng khi hàm trả về). */
@FunctionalInterface
public interface ReportSink {

    void accept(ReportHeaders headers, InputStream body) throws IOException;
}
