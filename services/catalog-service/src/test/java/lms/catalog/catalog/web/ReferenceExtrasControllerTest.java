package lms.catalog.catalog.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.application.CreateReferenceUseCase;
import lms.catalog.catalog.application.GetReferenceUseCase;
import lms.catalog.catalog.application.ReferenceOptionsUseCase;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SearchReferencesUseCase;
import lms.catalog.catalog.application.SetReferenceStatusUseCase;
import lms.catalog.catalog.application.UpdateReferenceUseCase;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.infrastructure.web.AuthorController;
import lms.catalog.catalog.infrastructure.web.CategoryController;
import lms.catalog.catalog.infrastructure.web.CatalogExceptionHandler;
import lms.catalog.catalog.infrastructure.web.PublisherController;
import lms.catalog.catalog.infrastructure.web.ShelfLocationController;
import lms.catalog.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * WebMvc cho thông tin bổ sung của tác giả, nhà xuất bản, vị trí kệ (V8) với use case thật trên kho trong bộ nhớ:
 * ghi, đọc lại, PUT thay toàn bộ, validation, thể loại không có trường bổ sung.
 */
@WebMvcTest({AuthorController.class, PublisherController.class, ShelfLocationController.class,
        CategoryController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, ReferenceExtrasControllerTest.Config.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ReferenceExtrasControllerTest {

    private static final UUID TENANT = UUID.randomUUID();

    @TestConfiguration
    static class Config {
        @Bean
        ReferenceRepository referenceRepository() {
            return new MemoryReferences();
        }

        @Bean
        ReferenceUseCases referenceUseCases(ReferenceRepository r) {
            return new ReferenceUseCases(new CreateReferenceUseCase(r), new UpdateReferenceUseCase(r),
                    new SetReferenceStatusUseCase(r), new GetReferenceUseCase(r), new SearchReferencesUseCase(r),
                    new ReferenceOptionsUseCase(r));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("admin", true));
    }

    private String create(String path, String body) throws Exception {
        String res = mvc.perform(post(path).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void tacGiaGhiDocVaPutThayToanBo() throws Exception {
        String id = create("/internal/authors", "{\"name\":\"Stephen Hawking\",\"birthYear\":1942,\"deathYear\":2018,"
                + "\"nationality\":\"Anh\",\"photoUrl\":\"https://x.example/h.jpg\",\"website\":\"https://hawking.example\"}");
        mvc.perform(get("/internal/authors/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthYear").value(1942))
                .andExpect(jsonPath("$.deathYear").value(2018))
                .andExpect(jsonPath("$.nationality").value("Anh"))
                .andExpect(jsonPath("$.photoUrl").value("https://x.example/h.jpg"))
                .andExpect(jsonPath("$.website").value("https://hawking.example"));
        mvc.perform(get("/internal/authors").param("keyword", "hawking").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nationality").value("Anh"));
        // PUT thiếu trường bổ sung = xóa
        mvc.perform(put("/internal/authors/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stephen Hawking\",\"nationality\":\"Vương quốc Anh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationality").value("Vương quốc Anh"))
                .andExpect(jsonPath("$.birthYear").doesNotExist());
    }

    @Test
    void nhaXuatBanVaViTriKe() throws Exception {
        String pid = create("/internal/publishers", "{\"name\":\"NXB Trẻ\",\"country\":\"Việt Nam\",\"foundedYear\":1981,"
                + "\"website\":\"https://nxbtre.example\",\"address\":\"TP.HCM\"}");
        mvc.perform(get("/internal/publishers/" + pid).with(admin()))
                .andExpect(jsonPath("$.country").value("Việt Nam"))
                .andExpect(jsonPath("$.foundedYear").value(1981))
                .andExpect(jsonPath("$.website").value("https://nxbtre.example"))
                .andExpect(jsonPath("$.address").value("TP.HCM"));
        String sid = create("/internal/shelf-locations",
                "{\"code\":\"VH-01\",\"floor\":\"Tầng 2\",\"zone\":\"Khu Văn học\",\"capacity\":60}");
        mvc.perform(get("/internal/shelf-locations/" + sid).with(admin()))
                .andExpect(jsonPath("$.code").value("VH-01"))
                .andExpect(jsonPath("$.floor").value("Tầng 2"))
                .andExpect(jsonPath("$.zone").value("Khu Văn học"))
                .andExpect(jsonPath("$.capacity").value(60));
        mvc.perform(put("/internal/shelf-locations/" + sid).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"VH-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floor").doesNotExist())
                .andExpect(jsonPath("$.capacity").doesNotExist());
    }

    @Test
    void theLoaiKhongCoTruongBoSung() throws Exception {
        String id = create("/internal/categories", "{\"name\":\"Văn học\"}");
        mvc.perform(get("/internal/categories/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Văn học"))
                .andExpect(jsonPath("$.extras").doesNotExist())
                .andExpect(jsonPath("$.birthYear").doesNotExist());
    }

    @Test
    void saiRangBuocTra400() throws Exception {
        for (String[] bad : new String[][] {
                {"/internal/authors", "\"birthYear\":0"}, {"/internal/authors", "\"deathYear\":3001"},
                {"/internal/authors", "\"photoUrl\":\"ftp://x\""}, {"/internal/authors", "\"website\":\"javascript:alert(1)\""},
                {"/internal/authors", "\"nationality\":\"" + "a".repeat(101) + "\""},
                {"/internal/publishers", "\"foundedYear\":1399"}, {"/internal/publishers", "\"website\":\"x.example\""},
                {"/internal/publishers", "\"country\":\"" + "a".repeat(101) + "\""},
                {"/internal/publishers", "\"address\":\"" + "a".repeat(501) + "\""},
                {"/internal/shelf-locations", "\"capacity\":0"}, {"/internal/shelf-locations", "\"capacity\":100001"},
                {"/internal/shelf-locations", "\"floor\":\"" + "a".repeat(21) + "\""},
                {"/internal/shelf-locations", "\"zone\":\"" + "a".repeat(51) + "\""}}) {
            String nameField = bad[0].contains("shelf") ? "\"code\":\"X\"" : "\"name\":\"X\"";
            mvc.perform(post(bad[0]).with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{" + nameField + "," + bad[1] + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void namMatTruocNamSinhTra422() throws Exception {
        mvc.perform(post("/internal/authors").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lỗi\",\"birthYear\":1900,\"deathYear\":1899}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AUTHOR"));
    }

    /** Kho danh mục trong bộ nhớ, chỉ đủ cho các use case được gọi ở đây. */
    static class MemoryReferences implements ReferenceRepository {
        private final Map<UUID, Reference> rows = new LinkedHashMap<>();

        @Override
        public Reference save(Reference r) {
            rows.put(r.id(), r);
            return r;
        }

        @Override
        public Optional<Reference> findById(ReferenceKind kind, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(r -> r.kind() == kind);
        }

        @Override
        public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
            return ids.stream().map(rows::get).filter(r -> r != null && r.kind() == kind).toList();
        }

        @Override
        public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
            return rows.values().stream().anyMatch(r -> r.kind() == kind && r.nameKey().equals(nameKey)
                    && !r.id().equals(excludeId));
        }

        @Override
        public boolean hasChildren(UUID categoryId) {
            return false;
        }

        @Override
        public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
            List<Reference> all = new ArrayList<>(rows.values().stream().filter(r -> r.kind() == kind)
                    .filter(r -> keyword == null || r.nameKey().contains(keyword.toLowerCase())).toList());
            return new Page(all, all.size());
        }

        @Override
        public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
            return rows.values().stream().filter(r -> r.kind() == kind).limit(limit).toList();
        }
    }
}
