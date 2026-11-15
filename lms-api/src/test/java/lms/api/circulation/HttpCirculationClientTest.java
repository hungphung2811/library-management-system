package lms.api.circulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.circulation.client.http.HttpCirculationClient;
import lms.api.circulation.dto.BorrowBookRequest;
import lms.api.support.TokenRelayInterceptor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Máy khách gọi đúng đường dẫn nội bộ /internal/**, ánh xạ DTO và chuyển tiếp token của người dùng. */
class HttpCirculationClientTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiApiNoiBoChuyenTiepTokenVaAnhXaDto() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://circulation")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCirculationClient client = new HttpCirculationClient(builder.build());

        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        UUID title = UUID.randomUUID();
        String borrowing = "{\"id\":\"" + id + "\",\"userId\":\"" + user + "\",\"status\":\"BORROWING\","
                + "\"borrowDate\":\"2026-01-01\",\"dueDate\":\"2026-01-15\",\"renewCount\":0,"
                + "\"items\":[{\"bookCopyId\":\"" + copy + "\",\"returnDate\":null}],\"campoMoi\":1}";

        server.expect(requestTo("http://circulation/internal/borrowings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-nguoi-dung"))
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andRespond(withSuccess(borrowing, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings/" + id + "/returns"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookCopyId").value(copy.toString()))
                .andRespond(withSuccess(borrowing, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings/" + id + "/renewals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(borrowing, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(borrowing, MediaType.APPLICATION_JSON));

        String reservation = "{\"id\":\"" + id + "\",\"titleId\":\"" + title + "\",\"status\":\"WAITING\","
                + "\"queuePosition\":1,\"reservedAt\":\"2026-01-01T00:00:00Z\",\"copyId\":null,\"holdUntil\":null}";
        server.expect(requestTo("http://circulation/internal/reservations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.titleId").value(title.toString()))
                .andRespond(withSuccess(reservation, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/reservations"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + reservation + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/reservations/" + id))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(reservation, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/reservations/expirations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"expired\":4}", MediaType.APPLICATION_JSON));

        String fine = "{\"id\":\"" + id + "\",\"readerId\":\"" + user + "\",\"borrowingId\":\"" + id
                + "\",\"bookCopyId\":\"" + copy + "\",\"reason\":\"LOST\",\"amount\":120000,\"status\":\"UNPAID\","
                + "\"createdAt\":\"2026-01-20T00:00:00Z\",\"paidAt\":null}";
        String returned = "{\"id\":\"" + id + "\",\"userId\":\"" + user + "\",\"status\":\"RETURNED\","
                + "\"borrowDate\":\"2026-01-01\",\"dueDate\":\"2026-01-15\",\"renewCount\":0,"
                + "\"items\":[{\"bookCopyId\":\"" + copy + "\",\"returnDate\":\"2026-01-20\",\"condition\":\"LOST\"}],"
                + "\"fines\":[" + fine + "]}";
        String fines = "{\"readerId\":\"" + user + "\",\"totalUnpaid\":120000,\"fines\":[" + fine + "]}";
        server.expect(requestTo("http://circulation/internal/borrowings/returns"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.barcode").value("B-1"))
                .andRespond(withSuccess(returned, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings/losses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.bookCopyId").value(copy.toString()))
                .andRespond(withSuccess(returned, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings/mine"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + returned + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/borrowings?readerId=" + user))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[" + returned + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/fines/mine"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fines, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/fines?readerId=" + user))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fines, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://circulation/internal/fines/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.readerId").value(user.toString()))
                .andRespond(withSuccess("{\"readerId\":\"" + user + "\",\"collectedCount\":1,"
                        + "\"totalCollected\":120000,\"fines\":[" + fine + "]}", MediaType.APPLICATION_JSON));

        var created = client.borrow(new BorrowBookRequest(user, List.of(copy)));
        assertEquals(copy, created.items().get(0).bookCopyId());
        assertEquals("BORROWING", client.returnBook(id, copy).status());
        assertEquals(0, client.renew(id).renewCount());
        assertEquals(id, client.getBorrowing(id).id());
        assertEquals("WAITING", client.reserve(title).status());
        assertEquals(1, client.myReservations().get(0).queuePosition());
        assertEquals(title, client.cancelReservation(id).titleId());
        assertEquals(4, client.expireReservations().expired());
        var lost = client.returnCopy(new lms.api.circulation.dto.ReturnCopyRequest(null, "B-1", null));
        assertEquals("LOST", lost.items().get(0).condition());
        assertEquals("LOST", lost.fines().get(0).reason());
        assertEquals(1, client.reportLost(new lms.api.circulation.dto.ReportLostRequest(copy, null)).fines().size());
        assertEquals(1, client.myBorrowings().size());
        assertEquals(1, client.borrowingsOfReader(user).size());
        assertEquals(0, new java.math.BigDecimal("120000").compareTo(client.myFines().totalUnpaid()));
        assertEquals(1, client.finesOfReader(user).fines().size());
        assertEquals(1, client.collectFines(user).collectedCount());
        server.verify();
    }
}
