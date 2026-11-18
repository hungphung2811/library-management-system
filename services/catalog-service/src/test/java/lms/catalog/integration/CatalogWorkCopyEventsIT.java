package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lms.catalog.inventory.domain.CopyAvailableEvent;
import lms.catalog.inventory.domain.EditionWorkChangedEvent;
import lms.catalog.inventory.infrastructure.messaging.KafkaInventoryEventPublisher;
import lms.common.kernel.DomainEvent;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0026 trên Postgres thật (vai trò catalog_app, RLS): truy vấn titleId -> (workId, language) mà CopyAvailableEvent dùng,
 * vòng đời LOANED -> LOST -> AVAILABLE (hoàn khoản) phát CopyAvailableEvent mang workId + language đúng, bản sao AVAILABLE ->
 * LOST bị cấm, gộp tác phẩm phát EditionWorkChangedEvent cho từng ấn bản bị chuyển với oldWorkId/newWorkId đúng, và dữ liệu
 * không rò giữa tenant. Chạy: -Dtest=CatalogWorkCopyEventsIT -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogWorkCopyEventsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean KafkaInventoryEventPublisher publisher;

    final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor admin(UUID t) {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", t.toString()));
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int expected)
            throws Exception {
        return call(tenant, b, expected);
    }

    private JsonNode call(UUID t, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int expected)
            throws Exception {
        String body = mvc.perform(b.with(admin(t))).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode title(UUID t, String name, String extra) throws Exception {
        return call(t, post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"" + name + "\",\"author\":\"Rowling\",\"isbn\":\"978-" + sfx() + "\",\"coverPrice\":1000" + extra
                        + "}"), 201);
    }

    private String copy(UUID t, String titleId) throws Exception {
        return call(t, post("/internal/book-copies").contentType(MediaType.APPLICATION_JSON)
                .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"BC-" + sfx() + "\"}"), 201).get("id").asText();
    }

    private JsonNode setStatus(UUID t, String copyId, String target, int expected) throws Exception {
        return call(t, put("/internal/book-copies/" + copyId + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + target + "\"}"), expected);
    }

    private JsonNode setStatus(String copyId, String target, int expected) throws Exception {
        return setStatus(tenant, copyId, target, expected);
    }

    private List<DomainEvent> published() {
        ArgumentCaptor<DomainEvent> c = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, atLeastOnce()).publish(c.capture());
        return c.getAllValues();
    }

    @Test
    void matLaTimLaiPhatCopyAvailableEventMangWorkIdVaNgonNgu() throws Exception {
        String s = sfx();
        JsonNode vi = title(tenant, "HP VI " + s, ",\"language\":\"vi\"");
        String copy = copy(tenant, vi.get("id").asText());
        clearInvocations(publisher);
        setStatus(copy, "LOANED", 200);
        setStatus(copy, "LOST", 200);
        // LOST -> AVAILABLE (ngoại lệ hoàn khoản)
        setStatus(copy, "AVAILABLE", 200);
        CopyAvailableEvent ev = published().stream().filter(CopyAvailableEvent.class::isInstance)
                .map(CopyAvailableEvent.class::cast).reduce((a, b) -> b).orElseThrow();
        assertThat(ev.copyId().toString()).isEqualTo(copy);
        assertThat(ev.titleId().toString()).isEqualTo(vi.get("id").asText());
        assertThat(ev.workId().toString()).isEqualTo(vi.get("workId").asText());
        assertThat(ev.language()).isEqualTo("vi");
        // idempotent: gọi lại không phát thêm
        clearInvocations(publisher);
        setStatus(copy, "AVAILABLE", 200);
        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    @Test
    void banSaoAvailableKhongTheNhayThangSangLostVaLostKhongVeReserved() throws Exception {
        JsonNode t = title(tenant, "X " + sfx(), "");
        String copy = copy(tenant, t.get("id").asText());
        setStatus(copy, "LOST", 409);
        setStatus(copy, "LOANED", 200);
        setStatus(copy, "LOST", 200);
        setStatus(copy, "RESERVED", 409);
        setStatus(copy, "LOANED", 409);
        // LOST -> DAMAGED (tìm lại nhưng hỏng)
        JsonNode r = setStatus(copy, "DAMAGED", 200);
        assertThat(r.get("status").asText()).isEqualTo("DAMAGED");
    }

    @Test
    void anBanKhongNgonNguPhatEventNgonNguNull() throws Exception {
        JsonNode t = title(tenant, "NoLang " + sfx(), "");
        String copy = copy(tenant, t.get("id").asText());
        clearInvocations(publisher);
        setStatus(copy, "LOANED", 200);
        setStatus(copy, "AVAILABLE", 200);
        CopyAvailableEvent ev = published().stream().filter(CopyAvailableEvent.class::isInstance)
                .map(CopyAvailableEvent.class::cast).findFirst().orElseThrow();
        assertThat(ev.workId()).isNotNull();
        assertThat(ev.language()).isNull();
    }

    @Test
    void gopTacPhamPhatEditionWorkChangedChoTungAnBanBiChuyen() throws Exception {
        String s = sfx();
        JsonNode a = title(tenant, "A " + s, ",\"language\":\"vi\"");
        JsonNode b1 = title(tenant, "B1 " + s, ",\"language\":\"en\"");
        String wa = a.get("workId").asText();
        String wb = b1.get("workId").asText();
        // thêm ấn bản thứ hai vào tác phẩm nguồn B
        JsonNode b2 = title(tenant, "B2 " + s, ",\"workId\":\"" + wb + "\",\"language\":\"fr\"");
        clearInvocations(publisher);
        call(post("/internal/works/" + wa + "/merge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkIds\":[\"" + wb + "\"]}"), 200);
        List<EditionWorkChangedEvent> evs = published().stream().filter(EditionWorkChangedEvent.class::isInstance)
                .map(EditionWorkChangedEvent.class::cast).toList();
        assertThat(evs).hasSize(2);
        assertThat(evs).extracting(e -> e.editionId().toString())
                .containsExactlyInAnyOrder(b1.get("id").asText(), b2.get("id").asText());
        assertThat(evs).allSatisfy(e -> {
            assertThat(e.oldWorkId().toString()).isEqualTo(wb);
            assertThat(e.newWorkId().toString()).isEqualTo(wa);
        });
        // bản sao của ấn bản đã chuyển giờ báo workId của tác phẩm đích khi trở lại kho
        String copy = copy(tenant, b1.get("id").asText());
        clearInvocations(publisher);
        setStatus(copy, "LOANED", 200);
        setStatus(copy, "AVAILABLE", 200);
        CopyAvailableEvent ca = published().stream().filter(CopyAvailableEvent.class::isInstance)
                .map(CopyAvailableEvent.class::cast).findFirst().orElseThrow();
        assertThat(ca.workId().toString()).isEqualTo(wa);
        assertThat(ca.language()).isEqualTo("en");
    }

    @Test
    void khongRoRiGiuaTenantVaGopTacPhamKhacTenantBiTuChoi() throws Exception {
        UUID other = UUID.randomUUID();
        String s = sfx();
        JsonNode mine = title(tenant, "Mine " + s, "");
        JsonNode theirs = title(other, "Theirs " + s, "");
        String copyOther = copy(other, theirs.get("id").asText());
        // tenant này không thấy bản sao/tác phẩm của tenant kia
        call(get("/internal/book-copies/" + copyOther), 404);
        call(get("/internal/works/" + theirs.get("workId").asText()), 404);
        setStatus(copyOther, "LOANED", 404);
        // không gộp được tác phẩm của tenant khác
        call(post("/internal/works/" + mine.get("workId").asText() + "/merge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkIds\":[\"" + theirs.get("workId").asText() + "\"]}"), 404);
        // ấn bản của tenant này không chuyển được sang tác phẩm của tenant kia
        call(put("/internal/books/" + mine.get("id").asText() + "/work").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workId\":\"" + theirs.get("workId").asText() + "\"}"), 404);
        assertThat(call(get("/internal/works/" + mine.get("workId").asText()), 200).get("editions")).hasSize(1);
    }
}
