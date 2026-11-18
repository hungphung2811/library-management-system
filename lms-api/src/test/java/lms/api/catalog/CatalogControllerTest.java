package lms.api.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyStatus;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
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
import org.mockito.ArgumentCaptor;
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
        when(catalog.searchTitles(eq("clean"), eq(true), eq(null))).thenReturn(List.of(
                new TitleView(TITLE, "Clean Code", "Martin", null, new BigDecimal("250000"), 2, null)));
        mvc.perform(get("/api/books").param("keyword", "clean").param("available", "true").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Clean Code"))
                .andExpect(jsonPath("$[0].availableCopies").value(2));
    }

    @Test
    void taoDauSachTraCreatedVaLocationTheoDuongDanFe() throws Exception {
        when(catalog.createTitle(any())).thenReturn(new TitleView(TITLE, "A", "B", null, BigDecimal.ONE, 0, null));
        mvc.perform(post("/api/books").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(HttpHeaders.LOCATION, "/api/books/" + TITLE));
    }

    @Test
    void suaDauSachChuyenTiepVaTraDauSachMoi() throws Exception {
        when(catalog.updateTitle(eq(TITLE), any())).thenReturn(
                new TitleView(TITLE, "Clean Code 2", "Martin", "978", new BigDecimal("300000"), 1, null));
        mvc.perform(put("/api/books/" + TITLE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Clean Code 2\",\"author\":\"Martin\",\"isbn\":\"978\",\"coverPrice\":300000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Clean Code 2"))
                .andExpect(jsonPath("$.availableCopies").value(1));
    }

    @Test
    void timKiemTraDayDuTenTruongThongTinBoSung() throws Exception {
        when(catalog.searchTitles(eq(null), eq(false), eq(null))).thenReturn(List.of(new TitleView(TITLE, "HP", "Rowling",
                null, new BigDecimal("120000"), 1, null, null, null, null, null, List.of(), "Phụ đề", 2017, "Tái bản",
                "vi", 368, "PAPERBACK", "Lý Lan", "Harry Potter", 1, "https://img.example/a.jpg",
                List.of("giả tưởng", "phép thuật"))));
        mvc.perform(get("/api/books").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subtitle").value("Phụ đề"))
                .andExpect(jsonPath("$[0].publicationYear").value(2017))
                .andExpect(jsonPath("$[0].edition").value("Tái bản"))
                .andExpect(jsonPath("$[0].language").value("vi"))
                .andExpect(jsonPath("$[0].pageCount").value(368))
                .andExpect(jsonPath("$[0].format").value("PAPERBACK"))
                .andExpect(jsonPath("$[0].translator").value("Lý Lan"))
                .andExpect(jsonPath("$[0].series").value("Harry Potter"))
                .andExpect(jsonPath("$[0].seriesNumber").value(1))
                .andExpect(jsonPath("$[0].coverImageUrl").value("https://img.example/a.jpg"))
                .andExpect(jsonPath("$[0].tags[1]").value("phép thuật"));
    }

    @Test
    void taoDauSachChuyenDuTruongMoiXuongClient() throws Exception {
        when(catalog.createTitle(any())).thenReturn(new TitleView(TITLE, "A", "B", null, BigDecimal.ONE, 0, null));
        mvc.perform(post("/api/books").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1,\"subtitle\":\"S\","
                                + "\"publicationYear\":2020,\"edition\":\"E\",\"language\":\"en\",\"pageCount\":10,"
                                + "\"format\":\"EBOOK\",\"translator\":\"T\",\"series\":\"Sr\",\"seriesNumber\":2,"
                                + "\"coverImageUrl\":\"http://x.example/c.png\",\"tags\":[\"a\",\"b\"]}"))
                .andExpect(status().isCreated());
        ArgumentCaptor<CreateTitleRequest> captor = ArgumentCaptor.forClass(CreateTitleRequest.class);
        verify(catalog).createTitle(captor.capture());
        CreateTitleRequest r = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("S", r.subtitle());
        org.junit.jupiter.api.Assertions.assertEquals(2020, r.publicationYear());
        org.junit.jupiter.api.Assertions.assertEquals("EBOOK", r.format());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("a", "b"), r.tags());
        org.junit.jupiter.api.Assertions.assertEquals(2, r.seriesNumber());
    }

    @Test
    void truongMoiSaiRangBuocBi400KhongGoiClient() throws Exception {
        String base = "{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1,";
        for (String bad : List.of("\"language\":\"VI\"", "\"language\":\"v\"", "\"publicationYear\":999",
                "\"pageCount\":0", "\"pageCount\":20001", "\"format\":\"AUDIO\"", "\"seriesNumber\":0",
                "\"coverImageUrl\":\"ftp://x.example/a\"", "\"coverImageUrl\":\"javascript:alert(1)\"",
                "\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]",
                "\"tags\":[\"" + "a".repeat(51) + "\"]", "\"subtitle\":\"" + "a".repeat(256) + "\"")) {
            mvc.perform(post("/api/books").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(base + bad + "}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/books/" + TITLE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                            .content(base + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void banSaoChuyenThongTinNhapKhoVaTraDayDuTruong() throws Exception {
        UUID copyId = UUID.randomUUID();
        var view = new CopyView(copyId, TITLE, "BC-1", "AVAILABLE", null, null, null, java.time.LocalDate.of(2024, 5, 1),
                "PURCHASE", 45000L, "GOOD", "Gáy mòn", java.time.LocalDate.of(2026, 1, 10));
        when(catalog.addCopy(any())).thenReturn(view);
        when(catalog.updateCopyAcquisition(eq(copyId), any())).thenReturn(view);
        String acq = "\"acquiredDate\":\"2024-05-01\",\"acquisitionSource\":\"PURCHASE\",\"purchasePrice\":45000,"
                + "\"conditionGrade\":\"GOOD\",\"conditionNote\":\"Gáy mòn\",\"lastInventoryDate\":\"2026-01-10\"";
        mvc.perform(post("/api/book-copies").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + TITLE + "\",\"barcode\":\"BC-1\"," + acq + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acquiredDate").value("2024-05-01"))
                .andExpect(jsonPath("$.acquisitionSource").value("PURCHASE"))
                .andExpect(jsonPath("$.purchasePrice").value(45000))
                .andExpect(jsonPath("$.conditionGrade").value("GOOD"))
                .andExpect(jsonPath("$.conditionNote").value("Gáy mòn"))
                .andExpect(jsonPath("$.lastInventoryDate").value("2026-01-10"));
        ArgumentCaptor<lms.api.catalog.dto.AddCopyRequest> c = ArgumentCaptor.forClass(lms.api.catalog.dto.AddCopyRequest.class);
        verify(catalog).addCopy(c.capture());
        org.junit.jupiter.api.Assertions.assertEquals(45000L, c.getValue().purchasePrice());
        org.junit.jupiter.api.Assertions.assertEquals("GOOD", c.getValue().conditionGrade());

        mvc.perform(put("/api/book-copies/" + copyId + "/acquisition").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + acq + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conditionGrade").value("GOOD"));
        mvc.perform(put("/api/book-copies/" + copyId + "/acquisition").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void banSaoSaiThongTinNhapKhoBi400() throws Exception {
        for (String bad : List.of("\"acquiredDate\":\"2999-01-01\"", "\"purchasePrice\":-1",
                "\"acquisitionSource\":\"GIFT\"", "\"conditionGrade\":\"BROKEN\"",
                "\"conditionNote\":\"" + "a".repeat(301) + "\"", "\"lastInventoryDate\":\"2999-01-01\"")) {
            mvc.perform(put("/api/book-copies/" + UUID.randomUUID() + "/acquisition").with(jwt())
                            .contentType(MediaType.APPLICATION_JSON).content("{" + bad + "}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/book-copies").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"titleId\":\"" + TITLE + "\",\"barcode\":\"B\"," + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void suaDauSachSaiDuLieuBi400VaKhongTokenBi401() throws Exception {
        mvc.perform(put("/api/books/" + TITLE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"author\":\"B\",\"coverPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(put("/api/books/" + TITLE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suaDauSachTrungIsbnChuyenNguyen409() throws Exception {
        when(catalog.updateTitle(eq(TITLE), any())).thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT,
                "Conflict", HttpHeaders.EMPTY,
                "{\"code\":\"DUPLICATE_ISBN\",\"message\":\"x\",\"details\":[]}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(put("/api/books/" + TITLE).with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"author\":\"B\",\"isbn\":\"1\",\"coverPrice\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ISBN"));
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

        when(catalog.listCopies(TITLE, null)).thenThrow(new ResourceAccessException("down", new SocketTimeoutException()));
        mvc.perform(get("/api/book-copies").param("titleId", TITLE.toString()).with(jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void traBanSaoTheoIdVaMaVachChoFe() throws Exception {
        UUID copy = UUID.randomUUID();
        CopyDetailsView view = new CopyDetailsView(copy, TITLE, "Clean Code", "CC-1", "LOANED", new BigDecimal("250000"));
        when(catalog.getCopy(copy)).thenReturn(view);
        when(catalog.getCopyByBarcode("CC-1")).thenReturn(view);
        mvc.perform(get("/api/book-copies/" + copy).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleName").value("Clean Code"))
                .andExpect(jsonPath("$.status").value("LOANED"));
        mvc.perform(get("/api/book-copies/by-barcode/CC-1").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value("CC-1"));
    }

    @Test
    void doiTrangThaiBanSaoChuyenDungTrangThaiVaTuChoiGiaTriSai() throws Exception {
        UUID copy = UUID.randomUUID();
        when(catalog.changeCopyStatus(copy, CopyStatus.DAMAGED))
                .thenReturn(new CopyView(copy, TITLE, "CC-1", "DAMAGED", null));
        mvc.perform(put("/api/book-copies/" + copy + "/status").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DAMAGED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DAMAGED"));
        mvc.perform(put("/api/book-copies/" + copy + "/status").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/book-copies/" + copy + "/status").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BAY_BIEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void docPreviewTraVanBanThuanVaKhongTokenBi401() throws Exception {
        when(catalog.getPreview(TITLE)).thenReturn(
                new lms.api.catalog.dto.TitlePreviewView(TITLE, "Clean Code", "Mô tả", "<b>x</b>"));
        mvc.perform(get("/api/books/" + TITLE + "/preview").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Clean Code"))
                .andExpect(jsonPath("$.description").value("Mô tả"))
                .andExpect(jsonPath("$.previewText").value("<b>x</b>"))
                .andExpect(jsonPath("$.isbn").doesNotExist());
        mvc.perform(get("/api/books/" + TITLE + "/preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/books/khong-phai-uuid/preview").with(jwt())).andExpect(status().isBadRequest());
    }

    @Test
    void suaPreviewChuyenBodyVaChoPhepNullDeXoa() throws Exception {
        when(catalog.updatePreview(eq(TITLE), any()))
                .thenReturn(new lms.api.catalog.dto.TitlePreviewView(TITLE, "A", null, null));
        mvc.perform(put("/api/books/" + TITLE + "/preview").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":null,\"previewText\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewText").doesNotExist());
        org.mockito.Mockito.verify(catalog).updatePreview(eq(TITLE),
                eq(new lms.api.catalog.dto.UpdateTitlePreviewRequest(null, "")));
        mvc.perform(put("/api/books/" + TITLE + "/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/books/" + TITLE + "/preview").with(jwt()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preview4xxCuaCatalogChuyenNguyen() throws Exception {
        when(catalog.updatePreview(eq(TITLE), any())).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"TITLE_PREVIEW_TOO_LONG\",\"message\":\"x\",\"details\":[]}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(put("/api/books/" + TITLE + "/preview").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"previewText\":\"dài\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_PREVIEW_TOO_LONG"));
        when(catalog.getPreview(TITLE)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "NF",
                HttpHeaders.EMPTY, "{\"code\":\"TITLE_NOT_FOUND\",\"message\":\"x\",\"details\":[]}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/books/" + TITLE + "/preview").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
    }

    @Test
    void chuyenTrangThaiSaiVongDoiCuaServiceNoiBoThanh409()throws Exception {
        UUID copy = UUID.randomUUID();
        when(catalog.changeCopyStatus(copy, CopyStatus.LOST)).thenThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY,
                "{\"code\":\"ILLEGAL_COPY_TRANSITION\",\"message\":\"x\",\"details\":[]}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(put("/api/book-copies/" + copy + "/status").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOST\"}"))
                .andExpect(status().isConflict());
    }
}
