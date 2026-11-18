package lms.catalog.inventory.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.catalog.config.SecurityConfiguration;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.ChangeCopyStatusUseCase;
import lms.catalog.inventory.application.CopyLookupView;
import lms.catalog.inventory.application.GetCopyUseCase;
import lms.catalog.inventory.application.ListCopiesUseCase;
import lms.catalog.inventory.application.LookupCopiesUseCase;
import lms.catalog.inventory.application.MoveCopyShelfLocationUseCase;
import lms.catalog.inventory.infrastructure.web.BookCopyController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** WebMvc cho POST /internal/book-copies/lookup: khóa BookCopyController/lookup, module LENDING, giới hạn lô. */
@WebMvcTest(BookCopyController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class BookCopyLookupControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String URL = "/internal/book-copies/lookup";

    @Autowired MockMvc mvc;

    @MockitoBean AddCopyUseCase addCopy;
    @MockitoBean ListCopiesUseCase listCopies;
    @MockitoBean GetCopyUseCase getCopy;
    @MockitoBean ChangeCopyStatusUseCase changeStatus;
    @MockitoBean MoveCopyShelfLocationUseCase moveShelf;
    @MockitoBean LookupCopiesUseCase lookupCopies;
    @MockitoBean lms.catalog.inventory.application.UpdateCopyAcquisitionUseCase updateAcquisition;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor withKey(String key, Object modules) {
        return jwt().jwt(j -> {
            j.claim("tenantId", TENANT.toString()).claim("permissions", List.of(key));
            if (modules != null) j.claim("modules", modules);
        }).authorities(new SimpleGrantedAuthority(key));
    }

    private static String body(List<UUID> ids) {
        StringBuilder sb = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(ids.get(i)).append('"');
        }
        return sb.append("]}").toString();
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(lookupCopies);
    }

    @Test
    void khoaChuaGanHoacKhoaKhacBi403() throws Exception {
        String b = body(List.of(UUID.randomUUID()));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(b)
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(b)
                        .with(withKey("BookCopyController/get", null)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(lookupCopies);
    }

    @Test
    void tenantKhongBatLendingBi403ModuleNotEnabled() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(List.of(UUID.randomUUID())))
                        .with(withKey("BookCopyController/lookup", List.of("SALES"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        verifyNoInteractions(lookupCopies);
    }

    @Test
    void coKhoaVaLendingTra200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        when(lookupCopies.execute(any())).thenReturn(List.of(
                new CopyLookupView(id, titleId, "Clean Code", "9780132350884", "BC-001", "LOANED")));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(List.of(id)))
                        .with(withKey("BookCopyController/lookup", List.of("LENDING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].titleId").value(titleId.toString()))
                .andExpect(jsonPath("$[0].titleName").value("Clean Code"))
                .andExpect(jsonPath("$[0].isbn").value("9780132350884"))
                .andExpect(jsonPath("$[0].barcode").value("BC-001"))
                .andExpect(jsonPath("$[0].status").value("LOANED"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist());
        verify(lookupCopies).execute(List.of(id));
    }

    @Test
    void bodyRongHoacQua100IdBi400() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}")
                        .with(withKey("BookCopyController/lookup", null)))
                .andExpect(status().isBadRequest());
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(UUID.randomUUID());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(many))
                        .with(withKey("BookCopyController/lookup", null)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lookupCopies);
    }
}
