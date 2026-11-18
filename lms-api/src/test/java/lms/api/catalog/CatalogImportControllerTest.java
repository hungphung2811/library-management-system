package lms.api.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.api.catalog.imports.CatalogImportController;
import lms.api.config.SecurityConfiguration;
import lms.api.imports.ImportForwarder;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/** Proxy nhập Excel catalog (ADR-0024 mục 3.3): danh sách trắng loại, ánh xạ đường dẫn nội bộ, chuyển nguyên lỗi 4xx. */
@WebMvcTest(CatalogImportController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CatalogImportControllerTest {

    private static final UUID ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean ImportForwarder forwarder;
    @MockitoBean JwtDecoder jwtDecoder;

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[] {1, 2});
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/catalog/imports/titles/template")).andExpect(status().isUnauthorized());
        verifyNoInteractions(forwarder);
    }

    @Test
    void loaiNgoaiDanhSachTrangBi404KhongChuyenTiep() throws Exception {
        mvc.perform(get("/api/catalog/imports/users/template").with(jwt())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_TYPE_NOT_FOUND"));
        mvc.perform(get("/api/catalog/imports/..%2Fusers/template").with(jwt())).andExpect(status().is4xxClientError());
        verifyNoInteractions(forwarder);
    }

    @Test
    void taiMauChuyenNguyenByteVaDisposition() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mau-titles.xlsx\"");
        when(forwarder.download("/internal/imports/titles/template"))
                .thenReturn(new ResponseEntity<>(new byte[] {9, 8, 7}, h, HttpStatus.OK));

        mvc.perform(get("/api/catalog/imports/titles/template").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mau-titles.xlsx\""))
                .andExpect(content().bytes(new byte[] {9, 8, 7}));
    }

    @Test
    void uploadChuyenMultipartSangDuongDanNoiBo() throws Exception {
        for (String type : new String[] {"titles", "authors", "publishers", "categories", "shelf-locations"}) {
            when(forwarder.upload(eq("/internal/imports/" + type), any()))
                    .thenReturn(ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"importId\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
            mvc.perform(multipart("/api/catalog/imports/" + type).file(file()).with(jwt()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.importId").value("x"));
        }
    }

    @Test
    void uploadThieuFileTra400() throws Exception {
        mvc.perform(multipart("/api/catalog/imports/authors").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_REQUIRED"));
        verifyNoInteractions(forwarder);
    }

    @Test
    void getRowsConfirmCancelErrorFileChuyenDungDuongDan() throws Exception {
        when(forwarder.download("/internal/imports/authors/" + ID))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{}".getBytes()));
        when(forwarder.download("/internal/imports/authors/" + ID + "/rows?status=INVALID&page=1&size=20"))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"items\":[]}".getBytes()));
        when(forwarder.post("/internal/imports/authors/" + ID + "/confirm", null))
                .thenReturn(ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body("{}".getBytes()));
        when(forwarder.delete("/internal/imports/authors/" + ID)).thenReturn(ResponseEntity.noContent().build());
        when(forwarder.download("/internal/imports/authors/" + ID + "/error-file"))
                .thenReturn(ResponseEntity.ok().body(new byte[] {1}));

        mvc.perform(get("/api/catalog/imports/authors/" + ID).with(jwt())).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/imports/authors/" + ID + "/rows").param("status", "INVALID").param("page", "1")
                        .param("size", "20").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(post("/api/catalog/imports/authors/" + ID + "/confirm").with(jwt())).andExpect(status().isAccepted());
        mvc.perform(delete("/api/catalog/imports/authors/" + ID).with(jwt())).andExpect(status().isNoContent());
        mvc.perform(get("/api/catalog/imports/authors/" + ID + "/error-file").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void thamSoRowsLaKyTuLaBi400() throws Exception {
        mvc.perform(get("/api/catalog/imports/authors/" + ID + "/rows").param("sheet", "a&b=c").with(jwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(forwarder);
    }

    @Test
    void importIdKhongPhaiUuidBi4xx() throws Exception {
        mvc.perform(get("/api/catalog/imports/authors/not-a-uuid").with(jwt())).andExpect(status().is4xxClientError());
        verifyNoInteractions(forwarder);
    }

    @Test
    void loi4xxCuaServiceDuocChuyenNguyenMaVaNoiDung() throws Exception {
        String body = "{\"code\":\"IMPORT_BUSY\",\"message\":\"Bận\",\"details\":[\"retryAfterSeconds=5\"]}";
        when(forwarder.post("/internal/imports/titles/" + ID + "/confirm", null)).thenThrow(
                HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", new HttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        mvc.perform(post("/api/catalog/imports/titles/" + ID + "/confirm").with(jwt()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("IMPORT_BUSY"));
        verify(forwarder).post("/internal/imports/titles/" + ID + "/confirm", null);
    }
}
