package lms.catalog.module;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.infrastructure.web.TitleController;
import lms.catalog.config.SecurityConfiguration;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.ChangeCopyStatusUseCase;
import lms.catalog.inventory.application.GetCopyUseCase;
import lms.catalog.inventory.application.ListCopiesUseCase;
import lms.catalog.inventory.infrastructure.web.BookCopyController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** ADR-0009: chặn module LENDING ở BookCopyController, TitleController (lõi) không bị chặn. */
@WebMvcTest({BookCopyController.class, TitleController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class ModuleBlockingTest {

    private static final String COPY_URL = "/internal/book-copies/" + UUID.randomUUID();
    private static final String TITLE_URL = "/internal/books/" + UUID.randomUUID();

    @Autowired MockMvc mvc;

    @MockitoBean AddCopyUseCase addCopy;
    @MockitoBean ListCopiesUseCase listCopies;
    @MockitoBean GetCopyUseCase getCopy;
    @MockitoBean ChangeCopyStatusUseCase changeStatus;
    @MockitoBean lms.catalog.inventory.application.MoveCopyShelfLocationUseCase moveShelfLocation;
    @MockitoBean lms.catalog.inventory.application.LookupCopiesUseCase lookupCopies;
    @MockitoBean lms.catalog.inventory.application.UpdateCopyAcquisitionUseCase updateAcquisition;
    @MockitoBean CreateTitleUseCase createTitle;
    @MockitoBean GetTitleUseCase getTitle;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesUseCase lookupTitles;
    @MockitoBean SearchTitlesUseCase searchTitles;
    @MockitoBean UpdateTitleUseCase updateTitle;
    @MockitoBean lms.catalog.catalog.application.LookupTitlesByKeywordUseCase lookupByKeyword;
    @MockitoBean lms.catalog.catalog.application.GetTitlePreviewUseCase getPreview;
    @MockitoBean lms.catalog.catalog.application.UpdateTitlePreviewUseCase updatePreview;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor token(String key, Object modules, boolean admin) {
        return jwt().jwt(j -> {
            j.claim("tenantId", UUID.randomUUID().toString()).claim("permissions", List.of(key));
            if (modules != null) j.claim("modules", modules);
            if (admin) j.claim("admin", true);
        }).authorities(new SimpleGrantedAuthority(key));
    }

    @Test
    void modulesCoLendingDuocQuaBookCopy() throws Exception {
        mvc.perform(get(COPY_URL).with(token("BookCopyController/get", List.of("LENDING"), false)))
                .andExpect(status().isOk());
    }

    @Test
    void modulesChiCoSalesBiChanBookCopyKeCaAdmin() throws Exception {
        for (boolean admin : new boolean[]{false, true}) {
            mvc.perform(get(COPY_URL).with(token("BookCopyController/get", List.of("SALES"), admin)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
    }

    @Test
    void tokenThieuClaimModulesVanQuaNhoCoLegacy() throws Exception {
        mvc.perform(get(COPY_URL).with(token("BookCopyController/get", null, false))).andExpect(status().isOk());
    }

    @Test
    void endpointLoiTitleKhongBiChanKhiChiCoSales() throws Exception {
        mvc.perform(get(TITLE_URL).with(token("TitleController/get", List.of("SALES"), false)))
                .andExpect(status().isOk());
    }
}
