package lms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.catalog.client.http.HttpCatalogClient;
import lms.api.circulation.client.http.HttpCirculationClient;
import lms.api.identity.client.http.HttpAccessClient;
import lms.api.identity.client.http.HttpDepartmentClient;
import lms.api.identity.client.http.HttpUserClient;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.support.TokenRelayInterceptor;
import lms.api.support.client.http.HttpSupportClient;
import java.util.function.Function;
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

/**
 * ADR-0008: Http*Client chỉ thêm query tenantId xuống /internal/** khi có giá trị (URI khớp chính xác nên vắng
 * thì không có tham số), và ánh xạ trường tenantId của DTO nội bộ sang DTO BFF (thiếu thì null).
 */
class CrossTenantReadClientTest {

    static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private record Fixture(RestClient rc, MockRestServiceServer server) {
    }

    private static Fixture fixture(String host) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://" + host)
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return new Fixture(builder.build(), server);
    }

    private static void expectGet(MockRestServiceServer s, String url, String json) {
        s.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void nguoiDungChuyenTenantIdKhiCoVaAnhXaTruongMoi() {
        Fixture f = fixture("identity");
        String item = "{\"id\":\"" + ID + "\",\"username\":\"a\",\"fullName\":\"A\",\"email\":\"a@x.vn\","
                + "\"departmentId\":null,\"status\":\"ACTIVE\",\"tenantId\":\"" + T + "\"}";
        String page = "{\"items\":[" + item + "],\"total\":1,\"page\":0,\"size\":20}";
        expectGet(f.server(), "http://identity/internal/users?keyword=a&tenantId=" + T, page);
        expectGet(f.server(), "http://identity/internal/users?keyword=a", page.replace(",\"tenantId\":\"" + T + "\"", ""));

        HttpUserClient client = new HttpUserClient(f.rc());
        assertEquals(T, client.search(new SearchUsersQuery("a", null, null, null, null, T.toString()))
                .items().get(0).tenantId());
        assertNull(client.search(new SearchUsersQuery("a", null, null, null, null, " ")).items().get(0).tenantId());
        f.server().verify();
    }

    @Test
    void phongBanRoleVaPermissionChuyenTenantIdKhiCo() {
        Fixture f = fixture("identity");
        String dept = "[{\"id\":\"" + ID + "\",\"code\":\"A\",\"name\":\"A\",\"parentId\":null,\"status\":\"ACTIVE\","
                + "\"tenantId\":\"" + T + "\"}]";
        String node = "[{\"id\":\"" + ID + "\",\"code\":\"A\",\"name\":\"A\",\"status\":\"ACTIVE\",\"children\":"
                + "[{\"id\":\"" + UUID.randomUUID() + "\",\"code\":\"B\",\"name\":\"B\",\"status\":\"ACTIVE\","
                + "\"children\":[],\"tenantId\":\"" + T + "\"}],\"tenantId\":\"" + T + "\"}]";
        String role = "[{\"id\":\"" + ID + "\",\"name\":\"r\",\"description\":\"d\",\"isAdmin\":false,"
                + "\"permissions\":[],\"tenantId\":\"" + T + "\"}]";
        expectGet(f.server(), "http://identity/internal/departments?status=ACTIVE&tenantId=" + T, dept);
        expectGet(f.server(), "http://identity/internal/departments", dept);
        expectGet(f.server(), "http://identity/internal/departments/tree?includeInactive=false&tenantId=" + T, node);
        expectGet(f.server(), "http://identity/internal/departments/tree?includeInactive=true", node);
        expectGet(f.server(), "http://identity/internal/roles?tenantId=" + T, role);
        expectGet(f.server(), "http://identity/internal/roles", role);
        expectGet(f.server(), "http://identity/internal/permissions?tenantId=" + T, "[]");
        expectGet(f.server(), "http://identity/internal/permissions", "[]");

        HttpDepartmentClient departments = new HttpDepartmentClient(f.rc());
        assertEquals(T, departments.list("ACTIVE", T.toString()).get(0).tenantId());
        assertEquals(1, departments.list(null, null).size());
        var tree = departments.tree(false, T.toString());
        assertEquals(T, tree.get(0).tenantId());
        assertEquals(T, tree.get(0).children().get(0).tenantId());
        assertEquals(1, departments.tree(true, "").size());

        HttpAccessClient access = new HttpAccessClient(f.rc());
        assertEquals(T, access.listRoles(T.toString()).get(0).tenantId());
        assertEquals(1, access.listRoles(null).size());
        assertEquals(0, access.listPermissions(T.toString()).size());
        assertEquals(0, access.listPermissions(null).size());
        f.server().verify();
    }

    @Test
    void sachVaBanSaoChuyenTenantIdKhiCo() {
        Fixture f = fixture("catalog");
        String title = "[{\"id\":\"" + ID + "\",\"name\":\"CC\",\"author\":\"M\",\"isbn\":null,\"coverPrice\":1,"
                + "\"availableCopies\":3,\"tenantId\":\"" + T + "\"}]";
        String copy = "[{\"id\":\"" + ID + "\",\"titleId\":\"" + ID + "\",\"barcode\":\"CC-1\","
                + "\"status\":\"AVAILABLE\",\"tenantId\":\"" + T + "\"}]";
        expectGet(f.server(), "http://catalog/internal/books?available=false&keyword=cc&tenantId=" + T, title);
        expectGet(f.server(), "http://catalog/internal/books?available=true",
                title.replace(",\"tenantId\":\"" + T + "\"", ""));
        expectGet(f.server(), "http://catalog/internal/book-copies?titleId=" + ID + "&tenantId=" + T, copy);
        expectGet(f.server(), "http://catalog/internal/book-copies?titleId=" + ID, copy);

        HttpCatalogClient client = new HttpCatalogClient(f.rc());
        assertEquals(T, client.searchTitles("cc", false, T.toString()).get(0).tenantId());
        assertNull(client.searchTitles(null, true, null).get(0).tenantId());
        assertEquals(T, client.listCopies(ID, T.toString()).get(0).tenantId());
        assertEquals(T, client.listCopies(ID, null).get(0).tenantId());
        f.server().verify();
    }

    @Test
    void muonVaPhatTheoDocGiaChuyenTenantIdKhiCo() {
        Fixture f = fixture("circulation");
        String fine = "{\"id\":\"" + ID + "\",\"readerId\":\"" + ID + "\",\"borrowingId\":\"" + ID
                + "\",\"bookCopyId\":\"" + ID + "\",\"reason\":\"LATE\",\"amount\":1000,\"status\":\"UNPAID\","
                + "\"createdAt\":\"2026-01-20T00:00:00Z\",\"paidAt\":null,\"tenantId\":\"" + T + "\"}";
        String borrowing = "[{\"id\":\"" + ID + "\",\"userId\":\"" + ID + "\",\"status\":\"RETURNED\","
                + "\"borrowDate\":\"2026-01-01\",\"dueDate\":\"2026-01-15\",\"renewCount\":0,\"items\":[],"
                + "\"fines\":[],\"tenantId\":\"" + T + "\"}]";
        String fines = "{\"readerId\":\"" + ID + "\",\"totalUnpaid\":1000,\"fines\":[" + fine + "]}";
        expectGet(f.server(), "http://circulation/internal/borrowings?readerId=" + ID + "&tenantId=" + T, borrowing);
        expectGet(f.server(), "http://circulation/internal/borrowings?readerId=" + ID, borrowing);
        expectGet(f.server(), "http://circulation/internal/fines?readerId=" + ID + "&tenantId=" + T, fines);
        expectGet(f.server(), "http://circulation/internal/fines?readerId=" + ID, fines);

        HttpCirculationClient client = new HttpCirculationClient(f.rc());
        assertEquals(T, client.borrowingsOfReader(ID, T.toString()).get(0).tenantId());
        assertEquals(1, client.borrowingsOfReader(ID, null).size());
        assertEquals(T, client.finesOfReader(ID, T.toString()).fines().get(0).tenantId());
        assertEquals(1, client.finesOfReader(ID, null).fines().size());
        f.server().verify();
    }

    @Test
    void auditLogsChuyenTenantIdKhiCo() {
        Fixture f = fixture("support");
        String page = "{\"items\":[{\"id\":\"" + ID + "\",\"eventId\":\"" + ID + "\",\"eventType\":\"E\","
                + "\"aggregateId\":\"agg\",\"occurredAt\":\"2026-01-01T00:00:00Z\","
                + "\"recordedAt\":\"2026-01-01T00:00:01Z\",\"payload\":\"{}\",\"tenantId\":\"" + T + "\"}],"
                + "\"total\":1,\"page\":0,\"size\":20}";
        expectGet(f.server(), "http://support/internal/audit-logs?page=0&size=20&tenantId=" + T, page);
        expectGet(f.server(), "http://support/internal/audit-logs?page=0&size=20", page);

        HttpSupportClient client = new HttpSupportClient(f.rc());
        assertEquals(T, client.searchAuditLogs(null, null, T.toString(), 0, 20).items().get(0).tenantId());
        assertEquals(1, client.searchAuditLogs(null, null, null, 0, 20).items().size());
        f.server().verify();
    }
}
