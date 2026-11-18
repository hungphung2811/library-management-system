package lms.imports.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.imports.core.ImportLimits;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.imports.engine.UploadGate;
import lms.imports.testsupport.FakeHandler;
import lms.imports.testsupport.XlsxFixtures;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import lms.security.permission.PermissionKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

class AbstractImportControllerTest {

    /** Controller con mẫu: khóa quyền phải theo TÊN LỚP CON này. */
    @RestController
    @RequestMapping("/internal/imports/sample")
    static class SampleImportController extends AbstractImportController {
        SampleImportController(ImportWorkflow workflow, FakeHandler handler, ImportActorResolver actors) {
            super(workflow, handler, actors);
        }
    }

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private ImportCommitExecutor executor;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        ImportLimits limits = ImportLimits.defaults();
        executor = new ImportCommitExecutor(4);
        ImportWorkflow workflow = new ImportWorkflow(new InMemoryImportSessionStore(limits, Clock.systemUTC()),
                new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits), new UploadGate(2), executor, limits, Clock.systemUTC());
        ImportActorResolver actors = () -> new ImportActor(TENANT, "alice", Set.of(), false);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(json);
        mvc = MockMvcBuilders.standaloneSetup(new SampleImportController(workflow, new FakeHandler(), actors))
                .setControllerAdvice(new ImportExceptionHandler(), new MultipartLimitHandler())
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(), converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private String upload() throws Exception {
        byte[] file = XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}, new Object[] {null, "B"}));
        MvcResult r = mvc.perform(multipart("/internal/imports/sample")
                        .file(new MockMultipartFile("file", "ds.xlsx", "application/octet-stream", file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.totals.valid").value(2))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode n = json.readTree(r.getResponse().getContentAsByteArray());
        return n.get("importId").asText();
    }

    @Test
    void templateDownloadsXlsxWithAttachmentName() throws Exception {
        mvc.perform(get("/internal/imports/sample/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", AbstractImportController.XLSX.toString()))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mau-sample.xlsx")))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void fullFlowThroughInheritedMappings() throws Exception {
        String id = upload();

        mvc.perform(get("/internal/imports/sample/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.importId").value(id)).andExpect(jsonPath("$.sheets[0].columns[1].key").value("name"));
        mvc.perform(get("/internal/imports/sample/" + id + "/rows").param("status", "VALID").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.items[0].extra").doesNotExist());
        int code = mvc.perform(post("/internal/imports/sample/" + id + "/confirm")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(200, 202);
        mvc.perform(get("/internal/imports/sample/" + id + "/error-file")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("loi-sample.xlsx")));
    }

    @Test
    void cancelReturnsNoContentThenNotFound() throws Exception {
        String id = upload();
        mvc.perform(delete("/internal/imports/sample/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/internal/imports/sample/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
    }

    @Test
    void errorsMapToDocumentedCodes() throws Exception {
        mvc.perform(multipart("/internal/imports/sample")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_REQUIRED"));
        mvc.perform(multipart("/internal/imports/sample")
                        .file(new MockMultipartFile("file", "x.xlsx", "application/octet-stream", "không phải xlsx".getBytes())))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("IMPORT_UNSUPPORTED_FILE"));
        mvc.perform(post("/internal/imports/sample/" + UUID.randomUUID() + "/confirm")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
        mvc.perform(get("/internal/imports/sample/khong-phai-uuid")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
        mvc.perform(get("/internal/imports/sample/" + UUID.randomUUID() + "/rows").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionKeysFollowTheSubclassNameAndOnlyUploadAndConfirmNeedAKey() throws Exception {
        String id = upload();
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        record Probe(String method, String path, boolean post, PermissionKeyResolver.Mode mode, String key) { }
        List<Probe> probes = List.of(
                new Probe("template", "/template", false, PermissionKeyResolver.Mode.AUTHENTICATED, null),
                new Probe("get", "/" + id, false, PermissionKeyResolver.Mode.AUTHENTICATED, null),
                new Probe("rows", "/" + id + "/rows", false, PermissionKeyResolver.Mode.AUTHENTICATED, null),
                new Probe("errorFile", "/" + id + "/error-file", false, PermissionKeyResolver.Mode.AUTHENTICATED, null),
                new Probe("confirm", "/" + id + "/confirm", true, PermissionKeyResolver.Mode.PERMISSION, "SampleImportController/confirm"));
        for (Probe p : probes) {
            MvcResult r = mvc.perform(p.post() ? post("/internal/imports/sample" + p.path()) : get("/internal/imports/sample" + p.path()))
                    .andReturn();
            HandlerMethod hm = (HandlerMethod) r.getHandler();
            PermissionKeyResolver.Resolved resolved = resolver.resolve(hm);
            assertThat(hm.getMethod().getName()).isEqualTo(p.method());
            assertThat(resolved.mode()).as(p.method()).isEqualTo(p.mode());
            assertThat(resolved.key()).as(p.method()).isEqualTo(p.key());
        }
        MvcResult up = mvc.perform(multipart("/internal/imports/sample")
                .file(new MockMultipartFile("file", "ds.xlsx", "application/octet-stream", XlsxFixtures.sample(List.<Object[]>of(new Object[] {null, "A"}))))).andReturn();
        PermissionKeyResolver.Resolved uploadKey = resolver.resolve((HandlerMethod) up.getHandler());
        assertThat(uploadKey.mode()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
        assertThat(uploadKey.key()).isEqualTo("SampleImportController/upload");

        MvcResult cancel = mvc.perform(delete("/internal/imports/sample/" + id)).andReturn();
        assertThat(resolver.resolve((HandlerMethod) cancel.getHandler()).mode()).isEqualTo(PermissionKeyResolver.Mode.AUTHENTICATED);
    }
}
