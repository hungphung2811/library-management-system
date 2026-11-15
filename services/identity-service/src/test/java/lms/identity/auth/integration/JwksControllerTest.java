package lms.identity.auth.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lms.identity.auth.infrastructure.web.JwksController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JwksController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, JwksControllerTest.Keys.class})
@TestPropertySource(properties = "lms.security.permission.enabled=true")
class JwksControllerTest {

    @TestConfiguration
    static class Keys {
        @Bean
        RSAKey rsaKey() throws Exception {
            return new RSAKeyGenerator(2048).keyID("test-kid").generate();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RSAKey rsaKey;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void congKhaiKhongCanTokenVaTraKhoaPublic() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value("test-kid"))
                .andExpect(jsonPath("$.keys[0].n").value(rsaKey.getModulus().toString()))
                .andExpect(jsonPath("$.keys[0].e").value(rsaKey.getPublicExponent().toString()));
    }

    @Test
    void khongRoRiThanhPhanKhoaRiengTu() throws Exception {
        String json = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (String priv : new String[]{"\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\""}) {
            org.junit.jupiter.api.Assertions.assertFalse(json.contains(priv), "Lộ thành phần riêng tư " + priv);
        }
    }

    @Test
    void phuongThucKhacGetBiTuChoi() throws Exception {
        // Không có handler cho POST và đường dẫn không nằm trong nhóm công khai cho POST
        int s = mvc.perform(post("/.well-known/jwks.json").with(org.springframework.security.test.web.servlet
                        .request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(s == 405 || s == 401 || s == 403, "status=" + s);
    }
}
