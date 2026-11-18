package lms.customer.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.customer.reader.client.CirculationReaderClient.ReservationRow;
import lms.customer.reader.client.http.HttpCatalogTitleClient;
import lms.customer.reader.client.http.HttpCirculationReaderClient;
import lms.customer.reader.client.http.HttpIdentityReaderClient;
import lms.customer.reader.dto.ReaderDtos.BorrowingView;
import lms.customer.reader.dto.ReaderDtos.FinesView;
import lms.customer.reader.dto.ReaderDtos.ReaderProfileView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Stub circulation/identity/catalog (MockRestServiceServer, T-R2): token ĐỘC GIẢ gửi circulation và identity, token DỊCH VỤ
 * gửi catalog; danh sách N đặt trước chỉ một lời gọi lookup; catalog lỗi thì tên null; trường nội bộ không lọt ra DTO.
 */
class HttpReaderClientsTest {

    private final UUID borrowingId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private final RestClient.Builder circulationBuilder = RestClient.builder().baseUrl("http://circulation");
    private final MockRestServiceServer circulationServer = MockRestServiceServer.bindTo(circulationBuilder).build();
    private final HttpCirculationReaderClient circulation = new HttpCirculationReaderClient(circulationBuilder.build());

    private final RestClient.Builder identityBuilder = RestClient.builder().baseUrl("http://identity");
    private final MockRestServiceServer identityServer = MockRestServiceServer.bindTo(identityBuilder).build();
    private final HttpIdentityReaderClient identity = new HttpIdentityReaderClient(identityBuilder.build());

    private final RestClient.Builder catalogBuilder = RestClient.builder().baseUrl("http://catalog");
    private final MockRestServiceServer catalogServer = MockRestServiceServer.bindTo(catalogBuilder).build();
    private final HttpCatalogTitleClient catalog = new HttpCatalogTitleClient(catalogBuilder.build());

    private String borrowingJson() {
        return "{\"id\":\"" + borrowingId + "\",\"userId\":\"" + UUID.randomUUID() + "\",\"status\":\"BORROWED\","
                + "\"borrowDate\":\"2026-11-01\",\"dueDate\":\"2026-11-15\",\"renewCount\":1,"
                + "\"items\":[{\"bookCopyId\":\"" + UUID.randomUUID() + "\",\"returnDate\":null,\"condition\":null}],"
                + "\"fines\":[{\"id\":\"" + UUID.randomUUID() + "\",\"readerId\":\"" + UUID.randomUUID() + "\"}],"
                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}";
    }

    // ---------- circulation ----------

    @Test
    void phieuMuonChuyenTokenDocGiaVaBoTruongNoiBo() throws Exception {
        circulationServer.expect(requestTo("http://circulation/internal/borrowings/mine"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess("[" + borrowingJson() + "]", MediaType.APPLICATION_JSON));
        circulationServer.expect(requestTo("http://circulation/internal/borrowings/" + borrowingId))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess(borrowingJson(), MediaType.APPLICATION_JSON));

        List<BorrowingView> list = circulation.myBorrowings("reader-token");
        BorrowingView one = circulation.myBorrowing("reader-token", borrowingId);

        assertThat(list).hasSize(1);
        assertThat(one.id()).isEqualTo(borrowingId);
        assertThat(one.items()).hasSize(1);
        assertThat(one.renewCount()).isEqualTo(1);
        assertThat(json.writeValueAsString(list)).doesNotContain("userId", "tenantId", "bookCopyId", "readerId", "fines");
        circulationServer.verify();
    }

    @Test
    void phatChuyenTokenDocGiaVaBoReaderIdBookCopyIdTenantId() throws Exception {
        String fine = "{\"id\":\"" + UUID.randomUUID() + "\",\"readerId\":\"" + UUID.randomUUID() + "\",\"borrowingId\":\""
                + borrowingId + "\",\"bookCopyId\":\"" + UUID.randomUUID() + "\",\"reason\":\"OVERDUE\","
                + "\"amount\":15000,\"status\":\"UNPAID\",\"createdAt\":\"2026-11-16T01:00:00Z\",\"paidAt\":null,"
                + "\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        circulationServer.expect(requestTo("http://circulation/internal/fines/mine"))
                .andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess("{\"readerId\":\"" + UUID.randomUUID() + "\",\"totalUnpaid\":15000,\"fines\":["
                        + fine + "]}", MediaType.APPLICATION_JSON));
        FinesView view = circulation.myFines("reader-token");
        assertThat(view.totalUnpaid()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(view.fines()).hasSize(1);
        assertThat(view.fines().get(0).borrowingId()).isEqualTo(borrowingId);
        assertThat(json.writeValueAsString(view)).doesNotContain("readerId", "bookCopyId", "tenantId");
        circulationServer.verify();
    }

    @Test
    void datTruocXemVaHuyChuyenTokenDocGiaVaBoCopyId() throws Exception {
        String reservation = "{\"id\":\"" + reservationId + "\",\"titleId\":\"" + titleId + "\",\"status\":\"READY\","
                + "\"queuePosition\":0,\"reservedAt\":\"2026-11-10T01:00:00Z\",\"copyId\":\"" + UUID.randomUUID()
                + "\",\"holdUntil\":\"2026-11-20\"}";
        circulationServer.expect(requestTo("http://circulation/internal/reservations"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess("[" + reservation + "]", MediaType.APPLICATION_JSON));
        circulationServer.expect(requestTo("http://circulation/internal/reservations/" + reservationId))
                .andExpect(method(HttpMethod.DELETE)).andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess(reservation.replace("READY", "CANCELLED"), MediaType.APPLICATION_JSON));

        List<ReservationRow> rows = circulation.myReservations("reader-token");
        ReservationRow cancelled = circulation.cancelReservation("reader-token", reservationId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).titleId()).isEqualTo(titleId);
        assertThat(rows.get(0).holdUntil()).hasToString("2026-11-20");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(json.writeValueAsString(rows)).doesNotContain("copyId");
        circulationServer.verify();
    }

    @Test
    void loiCuaCirculationDuocNemNguyenDeHandlerLoc() {
        circulationServer.expect(requestTo("http://circulation/internal/borrowings/" + borrowingId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BORROWING_NOT_FOUND\"}"));
        assertThatThrownBy(() -> circulation.myBorrowing("reader-token", borrowingId))
                .isInstanceOf(RestClientResponseException.class)
                .satisfies(ex -> assertThat(((RestClientResponseException) ex).getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void thanRongTuCirculationLa503Chung() {
        circulationServer.expect(requestTo("http://circulation/internal/fines/mine"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> circulation.myFines("reader-token")).isInstanceOf(PublicApiException.class)
                .satisfies(ex -> assertThat(((PublicApiException) ex).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---------- identity ----------

    @Test
    void theDocGiaChuyenTokenDocGiaVaBoUserId() throws Exception {
        identityServer.expect(requestTo("http://identity/internal/users/me/reader-profile"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer reader-token"))
                .andRespond(withSuccess("{\"userId\":\"" + UUID.randomUUID() + "\",\"cardNo\":\"LIB-1\",\"fullName\":\"An\","
                        + "\"email\":\"an@example.vn\",\"readerType\":\"STUDENT\",\"cardExpiryDate\":\"2027-01-01\","
                        + "\"status\":\"ACTIVE\",\"cardValid\":true,\"phone\":\"09\",\"dateOfBirth\":\"2000-01-01\","
                        + "\"gender\":\"MALE\",\"address\":\"HN\",\"faculty\":\"CNTT\",\"className\":\"K21\","
                        + "\"memberCode\":\"SV1\",\"cardIssuedDate\":\"2024-01-01\",\"internalNote\":\"x\"}",
                        MediaType.APPLICATION_JSON));
        ReaderProfileView view = identity.myReaderProfile("reader-token");
        assertThat(view.cardNo()).isEqualTo("LIB-1");
        assertThat(view.cardValid()).isTrue();
        assertThat(view.readerType()).isEqualTo("STUDENT");
        assertThat(json.writeValueAsString(view)).doesNotContain("userId", "internalNote");
        identityServer.verify();
    }

    // ---------- catalog lookup ----------

    @Test
    void lookupGuiTokenDichVuVaDungMotLoiGoiChoCaDanhSach() {
        UUID second = UUID.randomUUID();
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(content().json("{\"ids\":[\"" + titleId + "\",\"" + second + "\"]}", true))
                .andRespond(withSuccess("[{\"id\":\"" + titleId + "\",\"name\":\"Lập trình Java\",\"isbn\":\"9\","
                        + "\"coverPrice\":100000},{\"id\":\"" + second + "\",\"name\":\"Cấu trúc dữ liệu\"}]",
                        MediaType.APPLICATION_JSON));
        // id trùng và null bị gộp/bỏ: vẫn đúng một lời gọi (expect một lần; lời gọi thứ hai sẽ làm verify thất bại)
        Map<UUID, String> names = catalog.titleNames("svc-token", java.util.Arrays.asList(titleId, second, titleId, null));
        assertThat(names).containsEntry(titleId, "Lập trình Java").containsEntry(second, "Cấu trúc dữ liệu");
        catalogServer.verify();
    }

    @Test
    void lookupRongKhongGoiCatalog() {
        assertThat(catalog.titleNames("svc-token", List.of())).isEmpty();
        catalogServer.verify();
    }

    @Test
    void hon100IdChiaThanhLoToiDa100() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(UUID.randomUUID());
        }
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(catalog.titleNames("svc-token", ids)).isEmpty();
        catalogServer.verify();
    }

    @Test
    void catalogLoiThiTenNullVaKhongNem() {
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(catalog.titleNames("svc-token", List.of(titleId))).isEmpty();
        catalogServer.reset();
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\"}"));
        assertThat(catalog.titleNames("svc-token", List.of(titleId))).isEmpty();
    }
}
