package lms.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.client.ReferenceCatalogClient;
import lms.api.catalog.client.WorkCatalogClient;
import lms.api.catalog.dto.ReferencePageView;
import lms.api.catalog.dto.ReferenceView;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.dto.WorkDtos.ChangeEditionWorkRequest;
import lms.api.catalog.dto.WorkDtos.EditionSummaryView;
import lms.api.catalog.dto.WorkDtos.WorkDetailView;
import lms.api.catalog.dto.WorkDtos.WorkPageView;
import lms.api.catalog.dto.WorkDtos.WorkSearchQuery;
import lms.api.catalog.dto.WorkDtos.WorkSummaryView;
import lms.api.catalog.web.CatalogController;
import lms.api.catalog.web.ReferenceCatalogController;
import lms.api.catalog.web.WorkController;
import lms.api.config.SecurityConfiguration;
import lms.api.web.ApiExceptionHandler;
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

/** Hợp đồng FE của tác phẩm và bộ sách (ADR-0026): /api/works, /api/series, PUT /api/books/{id}/work, lọc /api/books. */
@WebMvcTest({WorkController.class, CatalogController.class, ReferenceCatalogController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class WorkControllerTest {

    static final UUID WORK = UUID.randomUUID();
    static final UUID SERIES = UUID.randomUUID();
    static final UUID TITLE = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean WorkCatalogClient works;
    @MockitoBean CatalogClient catalog;
    @MockitoBean ReferenceCatalogClient references;
    @MockitoBean JwtDecoder jwtDecoder;

    private static WorkDetailView detail() {
        return new WorkDetailView(WORK, "Harry Potter 1", "HP 1", "en", "Rowling", List.of(), null, null, null, SERIES,
                "Harry Potter", 1, null, "SINGLE", null,
                List.of(new EditionSummaryView(TITLE, "978", "HP 1 vi", "PAPERBACK", "vi", "A", null, 2020, 1, "Phần 1",
                        new BigDecimal("100000"), null, 3)));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/works")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/series")).andExpect(status().isUnauthorized());
        verifyNoInteractions(works, references);
    }

    @Test
    void timTacPhamChuyenQueryRecord() throws Exception {
        when(works.search(any())).thenReturn(new WorkPageView(List.of(new WorkSummaryView(WORK, "HP 1", null, "R",
                SERIES, "Harry Potter", 1, "SINGLE", 2, 5, null)), 1, 0, 20));
        mvc.perform(get("/api/works").param("keyword", "hp").param("seriesId", SERIES.toString()).param("page", "0")
                        .param("size", "20").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].seriesName").value("Harry Potter"))
                .andExpect(jsonPath("$.items[0].availableCopies").value(5));
        ArgumentCaptor<WorkSearchQuery> q = ArgumentCaptor.forClass(WorkSearchQuery.class);
        verify(works).search(q.capture());
        assertThat(q.getValue().keyword()).isEqualTo("hp");
        assertThat(q.getValue().seriesId()).isEqualTo(SERIES);
        assertThat(q.getValue().size()).isEqualTo(20);
    }

    @Test
    void chiTietTacPhamKemAnBan() throws Exception {
        when(works.get(WORK)).thenReturn(detail());
        mvc.perform(get("/api/works/" + WORK).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editions[0].titleId").value(TITLE.toString()))
                .andExpect(jsonPath("$.editions[0].partLabel").value("Phần 1"));
    }

    @Test
    void taoTacPhamTraCreatedVaLocation() throws Exception {
        when(works.create(any())).thenReturn(detail());
        mvc.perform(post("/api/works").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Harry Potter 1\",\"author\":\"Rowling\",\"kind\":\"SINGLE\"}").with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/works/" + WORK));
    }

    @Test
    void taoTacPhamSaiDuLieuBi400KhongGoiService() throws Exception {
        mvc.perform(post("/api/works").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"kind\":\"SINGLE\"}").with(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/works").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"A\",\"kind\":\"BOX\"}").with(jwt()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(works);
    }

    @Test
    void suaVaGopTacPham() throws Exception {
        when(works.update(eq(WORK), any())).thenReturn(detail());
        when(works.merge(eq(WORK), any())).thenReturn(detail());
        mvc.perform(put("/api/works/" + WORK).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"HP 1\",\"seriesId\":\"" + SERIES + "\",\"seriesNumber\":1}").with(jwt()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/works/" + WORK + "/merge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWorkIds\":[\"" + UUID.randomUUID() + "\"]}").with(jwt()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/works/" + WORK + "/merge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWorkIds\":[]}").with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loi4xxCuaCatalogChuyenNguyen() throws Exception {
        String body = "{\"code\":\"WORK_NOT_FOUND\",\"message\":\"Không thấy\"}";
        when(works.get(WORK)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "NF", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get("/api/works/" + WORK).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_NOT_FOUND"));
    }

    @Test
    void doiTacPhamCuaAnBan() throws Exception {
        when(catalog.changeWork(eq(TITLE), any())).thenReturn(new TitleView(TITLE, "A", "B", null, BigDecimal.ONE, 0,
                null));
        mvc.perform(put("/api/books/" + TITLE + "/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workId\":\"" + WORK + "\"}").with(jwt()))
                .andExpect(status().isOk());
        mvc.perform(put("/api/books/" + TITLE + "/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWork\":{\"title\":\"Tach\"}}").with(jwt()))
                .andExpect(status().isOk());
        ArgumentCaptor<ChangeEditionWorkRequest> c = ArgumentCaptor.forClass(ChangeEditionWorkRequest.class);
        verify(catalog, times(2)).changeWork(eq(TITLE), c.capture());
        assertThat(c.getAllValues().get(0).workId()).isEqualTo(WORK);
        assertThat(c.getAllValues().get(1).newWork().title()).isEqualTo("Tach");
        mvc.perform(put("/api/books/" + TITLE + "/work").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newWork\":{\"title\":\"\"}}").with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void locAnBanTheoTacPhamVaBoSach() throws Exception {
        when(catalog.searchTitles(any(), eq(false), any(), any(), any(), eq(WORK), eq(SERIES))).thenReturn(List.of(
                new TitleView(TITLE, "A", "B", null, BigDecimal.ONE, 0, null)));
        mvc.perform(get("/api/books").param("workId", WORK.toString()).param("seriesId", SERIES.toString()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TITLE.toString()));
    }

    @Test
    void boSachDungKhuonDanhMucThamChieu() throws Exception {
        when(references.search(eq("series"), any())).thenReturn(new ReferencePageView(
                List.of(new ReferenceView(SERIES, "Harry Potter", null, null, null, true, null)), 1, 0, 20));
        when(references.create(eq("series"), any())).thenReturn(
                new ReferenceView(SERIES, "Harry Potter", null, null, null, true, null));
        mvc.perform(get("/api/series").param("keyword", "harry").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Harry Potter"));
        mvc.perform(get("/api/series/options").with(jwt())).andExpect(status().isOk());
        mvc.perform(post("/api/series").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Harry Potter\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/series/" + SERIES));
        when(references.setStatus("series", SERIES, false)).thenReturn(
                new ReferenceView(SERIES, "Harry Potter", null, null, null, false, null));
        mvc.perform(put("/api/series/" + SERIES + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}").with(jwt()))
                .andExpect(status().isOk());
        verify(references).setStatus("series", SERIES, false);
    }
}
