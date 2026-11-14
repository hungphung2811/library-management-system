package lms.api.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.web.CatalogController;
import lms.api.config.SecurityConfiguration;
import lms.api.web.ApiExceptionHandler;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Hợp đồng FE của catalog: xác thực, ánh xạ DTO, chuyển nguyên lỗi 4xx, 5xx thành 502, mất kết nối thành 503. */
@WebMvcTest(CatalogController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CatalogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CatalogClient catalog;
    @MockitoBean JwtDecoder jwtDecoder;

    static final UUID TITLE = UUID.randomUUID();

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/books")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/book-copies").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void timKiemTraDanhSachChoFe() throws Exception {
        when(catalog.searchTitles(eq("clean"), eq(true))).thenReturn(List.of(
                new TitleView(TITLE, "Clean Code", "Martin", null, new BigDecimal("250000"), 2)));
        mvc.perform(get("/api/books").param("keyword", "clean").param("available", "true").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Clean Code"))
                .andExpect(jsonPath("$[0].availableCopies").value(2));
    }

    @Test
    void taoDauSachTraCreatedVaLocationTheoDuongDanFe() throws Exception {
        when(catalog.createTitle(any())).thenReturn(new TitleView(TITLE, "A", "B", null, BigDecimal.ONE, 0));
        mvc.perform(post("/api/books").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.LOCATION, "/api/books/" + TITLE));
    }

    @Test
    void duLieuSaiBi400TruocKhiGoiService() throws Exception {
        mvc.perform(post("/api/books").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"author\":\"B\",\"coverPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void loi4xxCuaServiceNoiBoDuocChuyenNguyenMaVaNoiDung() throws Exception {
        String body = "{\"code\":\"DUPLICATE_BARCODE\",\"message\":\"Mã vạch đã tồn tại: X\",\"details\":[]}";
        when(catalog.addCopy(any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(post("/api/book-copies").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\",\"barcode\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
    }

    @Test
    void permissionBiTuChoiOServiceNoiBoChuyenNguyen403() throws Exception {
        when(catalog.getTitle(TITLE)).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        mvc.perform(get("/api/books/" + TITLE).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_REJECTED"));
    }

    @Test
    void loi5xxKhongLoChiTietNoiBoVaMatKetNoiThanh503() throws Exception {
        when(catalog.getTitle(TITLE)).thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                "x", HttpHeaders.EMPTY, "stacktrace bí mật".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/books/" + TITLE).with(jwt()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bí mật"))));

        when(catalog.listCopies(TITLE)).thenThrow(new ResourceAccessException("down", new SocketTimeoutException()));
        mvc.perform(get("/api/book-copies").param("titleId", TITLE.toString()).with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }
}
