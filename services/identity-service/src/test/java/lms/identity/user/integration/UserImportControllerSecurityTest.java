package lms.identity.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.TemporaryPasswordGenerator;
import lms.identity.user.application.UserImportAuditPort;
import lms.identity.user.application.UserImportHandler;
import lms.identity.user.application.UserImportRowWriter;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.infrastructure.web.UserExceptionHandler;
import lms.identity.user.infrastructure.web.UserImportController;
import lms.imports.core.ImportLimits;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.imports.engine.UploadGate;
import lms.imports.web.ImportActorResolver;
import lms.imports.web.ImportExceptionHandler;
import lms.imports.web.MultipartLimitHandler;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * UserImportController (ADR-0024): khóa động theo TÊN LỚP CON ({@code UserImportController/upload|confirm|credentials}),
 * template/get/rows/cancel/error-file chỉ cần đăng nhập, và file mật khẩu tạm tải được đúng MỘT lần sau COMMITTED.
 */
@WebMvcTest(UserImportController.class)
@Import({SecurityConfiguration.class, UserExceptionHandler.class, PermissionAutoConfiguration.class,
        ImportExceptionHandler.class, MultipartLimitHandler.class, UserImportControllerSecurityTest.Wiring.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.security.permission.require-tenant=true"})
class UserImportControllerSecurityTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String ACTOR = "u-" + UUID.randomUUID();
    private static final String BASE = "/internal/imports/users";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TestConfiguration
    static class Wiring {
        @Bean
        ImportCommitExecutor importCommitExecutor() {
            return new ImportCommitExecutor(4);
        }

        @Bean
        ImportWorkflow importWorkflow(ImportCommitExecutor executor) {
            ImportLimits limits = ImportLimits.defaults();
            return new ImportWorkflow(new InMemoryImportSessionStore(limits, Clock.systemUTC()),
                    new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits), new UploadGate(2), executor, limits,
                    Clock.systemUTC());
        }

        @Bean
        UserImportHandler userImportHandler() {
            UserRepository users = Mockito.mock(UserRepository.class);
            AccountCredentialPort credentials = Mockito.mock(AccountCredentialPort.class);
            ReaderProfileRepository readers = Mockito.mock(ReaderProfileRepository.class);
            Mockito.when(users.existingUsernames(anyCollection())).thenReturn(Set.of());
            Mockito.when(users.existingEmails(anyCollection())).thenReturn(Set.of());
            Mockito.when(credentials.existingUsernames(anyCollection())).thenReturn(Set.of());
            Mockito.when(readers.existingCardNos(anyCollection())).thenReturn(Set.of());
            return new UserImportHandler(users, credentials, readers, Mockito.mock(UserImportRowWriter.class),
                    new TemporaryPasswordGenerator(), Mockito.mock(UserImportAuditPort.class), Clock.systemUTC());
        }

        @Bean
        ImportActorResolver importActorResolver() {
            return () -> new ImportActor(UUID.fromString(TENANT), currentActor, Set.of(), false);
        }
    }

    /** Mỗi test một người gọi riêng: giới hạn 3 phiên mở trên mỗi người dùng nên không dồn phiên giữa các test. */
    static volatile String currentActor = ACTOR;

    @org.junit.jupiter.api.BeforeEach
    void newActor() {
        currentActor = "u-" + UUID.randomUUID();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor withKey(String... keys) {
        return jwt().jwt(j -> j.subject(ACTOR).claim("tenantId", TENANT).claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new));
    }

    private static RequestPostProcessor noKey() {
        return withKey();
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ACTOR).claim("tenantId", TENANT).claim("admin", true));
    }

    private static byte[] workbook(String... usernames) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("NguoiDung");
            Row h = s.createRow(0);
            String[] header = {"username", "fullName", "email"};
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            int r = 1;
            for (String u : usernames) {
                Row row = s.createRow(r++);
                row.createCell(0).setCellValue(u);
                row.createCell(1).setCellValue("Họ tên " + u);
                row.createCell(2).setCellValue(u + "@lib.vn");
            }
            Sheet meta = wb.createSheet("_meta");
            meta.createRow(0).createCell(0).setCellValue("type");
            meta.getRow(0).createCell(1).setCellValue("users");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String upload(String... usernames) throws Exception {
        String body = mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "nd.xlsx", XLSX, workbook(usernames)))
                        .with(withKey("UserImportController/upload")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totals.valid").value(usernames.length))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("importId").asText();
    }

    private void confirmAndWait(String id) throws Exception {
        mvc.perform(post(BASE + "/" + id + "/confirm").with(withKey("UserImportController/confirm")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 202));
        for (int i = 0; i < 100; i++) {
            JsonNode view = json.readTree(mvc.perform(get(BASE + "/" + id).with(noKey())).andReturn().getResponse()
                    .getContentAsString());
            if ("COMMITTED".equals(view.get("status").asText())) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Phiên không COMMITTED kịp thời");
    }

    @Test
    void uploadAndConfirmNeedTheirOwnKeys() throws Exception {
        byte[] file = workbook("nva");
        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "nd.xlsx", XLSX, file)).with(noKey()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "nd.xlsx", XLSX, file))
                        .with(withKey("UserImportController/confirm")))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/confirm").with(withKey("UserImportController/upload")))
                .andExpect(status().isForbidden());
        // đúng khóa: qua lớp quyền (không tồn tại phiên thì 404 của import, không phải 403)
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/confirm").with(withKey("UserImportController/confirm")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
        upload("nva");
    }

    @Test
    void adminBypassesKeysAndOtherEndpointsOnlyNeedLogin() throws Exception {
        mvc.perform(get(BASE + "/template").with(noKey())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/template")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "nd.xlsx", XLSX, workbook("adm"))).with(admin()))
                .andExpect(status().isCreated());
        String id = upload("nva");
        mvc.perform(get(BASE + "/" + id).with(noKey())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id + "/rows").with(noKey())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].values.username").value("nva"));
        mvc.perform(get(BASE + "/" + id + "/error-file").with(noKey())).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/" + id).with(noKey()))
                .andExpect(status().isNoContent());
    }

    @Test
    void credentialsNeedTheirKeyAndAreDownloadableExactlyOnceAfterCommit() throws Exception {
        String id = upload("nva", "tvb");
        // Chưa COMMITTED: chưa có file mật khẩu
        mvc.perform(get(BASE + "/" + id + "/credentials").with(withKey("UserImportController/credentials")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_INVALID_STATE"));
        confirmAndWait(id);

        mvc.perform(get(BASE + "/" + id + "/credentials").with(noKey())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id + "/credentials").with(withKey("UserImportController/upload", "UserImportController/confirm")))
                .andExpect(status().isForbidden());

        byte[] file = mvc.perform(get(BASE + "/" + id + "/credentials").with(withKey("UserImportController/credentials")))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).isEqualTo(XLSX))
                .andExpect(r -> assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> logins = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet s = wb.getSheetAt(0);
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                logins.add(s.getRow(i).getCell(0).getStringCellValue());
                assertThat(s.getRow(i).getCell(1).getStringCellValue()).hasSize(14);
            }
        }
        assertThat(logins).containsExactlyInAnyOrder("nva", "tvb");

        // Lần hai: đã tải
        mvc.perform(get(BASE + "/" + id + "/credentials").with(withKey("UserImportController/credentials")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("IMPORT_CREDENTIALS_GONE"));
    }

    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    private static String seedSql() throws Exception {
        var found = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/identity/V31__import_permissions.sql");
        assertThat(found).as("V31 trên classpath (jar system-service)").hasSize(1);
        try (var in = found[0].getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Khóa, method, đường dẫn thật của UserImportController phải khớp dòng seed identity-service trong V31. */
    @Test
    void permissionKeysOfTheRealControllerMatchTheV31Seed() throws Exception {
        var resolver = new lms.security.permission.PermissionKeyResolver();
        java.util.Map<String, String> actual = new java.util.TreeMap<>();
        for (var e : handlerMapping.getHandlerMethods().entrySet()) {
            var h = e.getValue();
            if (!h.getBeanType().equals(UserImportController.class)) continue;
            var resolved = resolver.resolve(h);
            if (resolved.mode() != lms.security.permission.PermissionKeyResolver.Mode.PERMISSION) continue;
            String method = e.getKey().getMethodsCondition().getMethods().iterator().next().name();
            String path = e.getKey().getPathPatternsCondition().getPatternValues().iterator().next();
            actual.put(resolved.key(), method + " " + path.replace("/internal/", "/api/identity/"));
        }
        java.util.Map<String, String> seeded = new java.util.TreeMap<>();
        var m = java.util.regex.Pattern.compile(
                "\\('(UserImportController/[a-z]+)',\\s*'identity-service',\\s*'([A-Z]+)',\\s*'([^']+)'\\)")
                .matcher(seedSql());
        while (m.find()) seeded.put(m.group(1), m.group(2) + " " + m.group(3));
        assertThat(actual).hasSize(3).containsKeys("UserImportController/upload", "UserImportController/confirm",
                "UserImportController/credentials");
        assertThat(normalize(seeded)).isEqualTo(normalize(actual));
    }

    private static java.util.Map<String, String> normalize(java.util.Map<String, String> in) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        in.forEach((k, v) -> out.put(k, v.replaceAll("\\{[^}/]+}", "{}")));
        return out;
    }
}
