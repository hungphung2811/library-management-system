package lms.api.identity;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.identity.imports.UserImportController;
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

/** Proxy /api/identity/imports/users/**: cần đăng nhập, chuyển đúng đường dẫn nội bộ, credentials không cache. */
@WebMvcTest(UserImportController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class UserImportControllerTest {

    private static final String BASE = "/api/identity/imports/users";
    private static final String INTERNAL = "/internal/imports/users";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @MockitoBean ImportForwarder forwarder;
    @MockitoBean JwtDecoder jwtDecoder;

    private static ResponseEntity<byte[]> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(BASE + "/template")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/credentials")).andExpect(status().isUnauthorized());
        verifyNoInteractions(forwarder);
    }

    @Test
    void forwardsEveryEndpointToTheFixedInternalPath() throws Exception {
        UUID id = UUID.randomUUID();
        when(forwarder.download(any())).thenReturn(json(HttpStatus.OK, "{}"));
        when(forwarder.upload(any(), any())).thenReturn(json(HttpStatus.CREATED, "{\"importId\":\"" + id + "\"}"));
        when(forwarder.post(any(), any())).thenReturn(json(HttpStatus.ACCEPTED, "{}"));
        when(forwarder.delete(any())).thenReturn(ResponseEntity.noContent().build());

        mvc.perform(get(BASE + "/template").with(jwt())).andExpect(status().isOk());
        verify(forwarder).download(INTERNAL + "/template");

        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "nd.xlsx", XLSX, new byte[] {1, 2})).with(jwt()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.importId").value(id.toString()));
        verify(forwarder).upload(eq(INTERNAL), any());

        mvc.perform(get(BASE + "/" + id).with(jwt())).andExpect(status().isOk());
        verify(forwarder).download(INTERNAL + "/" + id);
        mvc.perform(get(BASE + "/" + id + "/rows").param("status", "INVALID").param("page", "1").param("size", "50")
                .param("khac", "bi-bo").with(jwt())).andExpect(status().isOk());
        verify(forwarder).download(INTERNAL + "/" + id + "/rows?status=INVALID&page=1&size=50");
        mvc.perform(post(BASE + "/" + id + "/confirm").with(jwt())).andExpect(status().isAccepted());
        verify(forwarder).post(INTERNAL + "/" + id + "/confirm", null);
        mvc.perform(get(BASE + "/" + id + "/error-file").with(jwt())).andExpect(status().isOk());
        verify(forwarder).download(INTERNAL + "/" + id + "/error-file");
        mvc.perform(delete(BASE + "/" + id).with(jwt())).andExpect(status().isNoContent());
        verify(forwarder).delete(INTERNAL + "/" + id);
    }

    @Test
    void uploadWithoutFileIsRejectedWithoutCallingTheService() throws Exception {
        mvc.perform(multipart(BASE).with(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(forwarder);
    }

    @Test
    void rowsRejectsUnsafeQueryValuesAndNonUuidIds() throws Exception {
        mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/rows").param("status", "a&b=c").with(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/khong-phai-uuid").with(jwt())).andExpect(status().is4xxClientError());
        verifyNoInteractions(forwarder);
    }

    @Test
    void credentialsAreForwardedAsAFileWithNoStoreAndServiceErrorsPassThrough() throws Exception {
        UUID id = UUID.randomUUID();
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.parseMediaType(XLSX));
        upstream.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mat-khau-tam-users.xlsx\"");
        when(forwarder.download(INTERNAL + "/" + id + "/credentials"))
                .thenReturn(new ResponseEntity<>(new byte[] {7, 7}, upstream, HttpStatus.OK));
        mvc.perform(get(BASE + "/" + id + "/credentials").with(jwt())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", XLSX))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("mat-khau-tam-users.xlsx")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        when(forwarder.download(INTERNAL + "/" + id + "/credentials")).thenThrow(HttpClientErrorException.create(
                HttpStatus.GONE, "Gone", new HttpHeaders(), "{\"code\":\"IMPORT_CREDENTIALS_GONE\",\"message\":\"đã tải\"}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        mvc.perform(get(BASE + "/" + id + "/credentials").with(jwt())).andExpect(status().isGone());
    }
}
