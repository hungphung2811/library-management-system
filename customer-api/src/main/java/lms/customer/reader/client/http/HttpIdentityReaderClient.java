package lms.customer.reader.client.http;

import java.time.LocalDate;
import lms.customer.reader.client.IdentityReaderClient;
import lms.customer.reader.dto.ReaderDtos.ReaderProfileView;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * {@code GET /internal/users/me/reader-profile} của identity-service bằng token độc giả. Chỉ đọc các trường cần dùng và
 * ánh xạ từng trường: {@code userId} không bao giờ ra công khai. 404 {@code READER_NOT_FOUND} được ném nguyên để
 * PublicExceptionHandler đổi thành {@code READER_PROFILE_NOT_FOUND}.
 */
public class HttpIdentityReaderClient implements IdentityReaderClient {

    private final RestClient client;

    public HttpIdentityReaderClient(RestClient client) {
        this.client = client;
    }

    @Override
    public ReaderProfileView myReaderProfile(String userToken) {
        UpstreamProfile p = client.get().uri("/internal/users/me/reader-profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamProfile.class);
        if (p == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return new ReaderProfileView(p.cardNo(), p.fullName(), p.email(), p.readerType(), p.cardExpiryDate(),
                p.status(), p.cardValid(), p.phone(), p.dateOfBirth(), p.gender(), p.address(), p.faculty(),
                p.className(), p.memberCode(), p.cardIssuedDate());
    }

    record UpstreamProfile(String cardNo, String fullName, String email, String readerType, LocalDate cardExpiryDate,
                           String status, boolean cardValid, String phone, LocalDate dateOfBirth, String gender,
                           String address, String faculty, String className, String memberCode,
                           LocalDate cardIssuedDate) {
    }
}
