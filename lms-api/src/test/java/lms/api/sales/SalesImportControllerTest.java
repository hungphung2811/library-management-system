package lms.api.sales;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.imports.ImportForwarder;
import lms.api.sales.imports.SalesImportController;
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

/** Proxy /api/sales/imports/{type}/**: cần đăng nhập, danh sách trắng loại, chuyển đúng đường dẫn nội bộ. */
@WebMvcTest(SalesImportController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesImportControllerTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @MockitoBean ImportForwarder forwarder;
    @MockitoBean JwtDecoder jwtDecoder;

    private static ResponseEntity<byte[]> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void canDangNhap() throws Exception {
        mvc.perform(get("/api/sales/imports/stock-items/template")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/sales/imports/suppliers").file(new MockMultipartFile("file", "a.xlsx", XLSX, new byte[] {1})))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(forwarder);
    }

    @Test
    void chuyenBayEndpointSangDuongDanNoiBoCuaTungLoai() throws Exception {
        UUID id = UUID.randomUUID();
        when(forwarder.download(any())).thenReturn(json(HttpStatus.OK, "{}"));
        when(forwarder.upload(any(), any())).thenReturn(json(HttpStatus.CREATED, "{\"importId\":\"" + id + "\"}"));
        when(forwarder.post(any(), any())).thenReturn(json(HttpStatus.ACCEPTED, "{}"));
        when(forwarder.delete(any())).thenReturn(ResponseEntity.noContent().build());

        for (String type : new String[] {"stock-items", "suppliers"}) {
            String base = "/api/sales/imports/" + type;
            String internal = "/internal/imports/" + type;
            mvc.perform(get(base + "/template").with(jwt())).andExpect(status().isOk());
            verify(forwarder).download(internal + "/template");
            mvc.perform(multipart(base).file(new MockMultipartFile("file", "nd.xlsx", XLSX, new byte[] {1, 2})).with(jwt()))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.importId").value(id.toString()));
            verify(forwarder).upload(eq(internal), any());
            mvc.perform(get(base + "/" + id).with(jwt())).andExpect(status().isOk());
            verify(forwarder).download(internal + "/" + id);
            mvc.perform(get(base + "/" + id + "/rows").param("status", "INVALID").param("sheet", "MatHang")
                    .param("page", "1").param("size", "50").param("khac", "bi-bo").with(jwt())).andExpect(status().isOk());
            verify(forwarder).download(internal + "/" + id + "/rows?status=INVALID&sheet=MatHang&page=1&size=50");
            mvc.perform(post(base + "/" + id + "/confirm").with(jwt())).andExpect(status().isAccepted());
            verify(forwarder).post(internal + "/" + id + "/confirm", null);
            mvc.perform(get(base + "/" + id + "/error-file").with(jwt())).andExpect(status().isOk());
            verify(forwarder).download(internal + "/" + id + "/error-file");
            mvc.perform(delete(base + "/" + id).with(jwt())).andExpect(status().isNoContent());
            verify(forwarder).delete(internal + "/" + id);
        }
    }

    @Test
    void loaiNgoaiDanhSachTrangBi404KhongGoiService() throws Exception {
        UUID id = UUID.randomUUID();
        for (String type : new String[] {"titles", "users", "authors"}) {
            mvc.perform(get("/api/sales/imports/" + type + "/template").with(jwt())).andExpect(status().isNotFound());
            mvc.perform(get("/api/sales/imports/" + type + "/" + id).with(jwt())).andExpect(status().isNotFound());
            mvc.perform(post("/api/sales/imports/" + type + "/" + id + "/confirm").with(jwt())).andExpect(status().isNotFound());
        }
        verifyNoInteractions(forwarder);
    }

    @Test
    void thieuFileVaTruyVanLaBiTuChoiTaiBff() throws Exception {
        mvc.perform(multipart("/api/sales/imports/suppliers").with(jwt())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sales/imports/suppliers/" + UUID.randomUUID() + "/rows").param("status", "a&b=c").with(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sales/imports/suppliers/khong-phai-uuid").with(jwt())).andExpect(status().is4xxClientError());
        verifyNoInteractions(forwarder);
    }

    @Test
    void loiCuaServiceDuocChuyenNguyen() throws Exception {
        UUID id = UUID.randomUUID();
        when(forwarder.post(any(), any())).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
                new HttpHeaders(), "{\"code\":\"PERMISSION_DENIED\",\"message\":\"thiếu quyền\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(post("/api/sales/imports/stock-items/" + id + "/confirm").with(jwt())).andExpect(status().isForbidden());
    }
}
