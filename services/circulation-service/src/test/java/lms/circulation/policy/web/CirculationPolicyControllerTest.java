package lms.circulation.policy.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.cache.NoOpRequestCache;
import lms.circulation.config.SecurityConfiguration;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.circulation.policy.infrastructure.cache.CachedPolicyReader;
import lms.circulation.policy.infrastructure.web.CirculationPolicyController;
import lms.circulation.policy.infrastructure.web.PolicyExceptionHandler;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Hợp đồng HTTP của chính sách mượn trả sau khi thêm ba trường nhắc hạn (ADR-0030 D11) và ma trận khóa quyền. Use case
 * thật chạy trên kho trong bộ nhớ; kiểm khóa bật lại. Không cần Docker.
 */
@WebMvcTest(CirculationPolicyController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, PolicyExceptionHandler.class,
        CirculationPolicyControllerTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CirculationPolicyControllerTest {

    static final UUID TENANT = UUID.randomUUID();
    static CirculationPolicySnapshot stored;

    static final CirculationPolicyRepository REPO = new CirculationPolicyRepository() {
        @Override
        public Optional<CirculationPolicySnapshot> find() {
            return Optional.ofNullable(stored);
        }

        @Override
        public CirculationPolicySnapshot save(CirculationPolicy policy, UUID by, Instant now) {
            stored = new CirculationPolicySnapshot(policy, stored == null ? 1 : stored.version() + 1, now, by);
            return stored;
        }
    };

    @TestConfiguration
    static class Beans {
        @Bean GetCirculationPolicyUseCase getPolicy() { return new GetCirculationPolicyUseCase(REPO); }
        @Bean CachedPolicyReader reader(GetCirculationPolicyUseCase g) {
            return new CachedPolicyReader(g, new NoOpRequestCache());
        }
        @Bean UpdateCirculationPolicyUseCase update() {
            return new UpdateCirculationPolicyUseCase(REPO, Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC));
        }
    }

    @Autowired MockMvc mvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static JwtRequestPostProcessor token(String... keys) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    private static final String BODY_BASE = "\"loanDays\":14,\"maxRenewals\":2,\"renewalDays\":14,"
            + "\"maxActiveLoansPerReader\":5,\"finePerDay\":5000,\"graceDays\":0,\"reservationHoldDays\":3";

    @Test
    void getChuaCauHinhTraMacDinhCoBaTruongNhacHan() throws Exception {
        stored = null;
        mvc.perform(get("/internal/circulation/policy").with(token("CirculationPolicyController/get")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueReminderDaysBefore").value(2))
                .andExpect(jsonPath("$.overdueReminderIntervalDays").value(3))
                .andExpect(jsonPath("$.overdueReminderMax").value(3))
                .andExpect(jsonPath("$.custom").value(false));
    }

    @Test
    void putLuuBaTruongNhacHanVaGetDocLai() throws Exception {
        stored = null;
        mvc.perform(put("/internal/circulation/policy").with(token("CirculationPolicyController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + BODY_BASE + ",\"version\":0,\"dueReminderDaysBefore\":5,"
                                + "\"overdueReminderIntervalDays\":7,\"overdueReminderMax\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueReminderDaysBefore").value(5))
                .andExpect(jsonPath("$.overdueReminderIntervalDays").value(7))
                .andExpect(jsonPath("$.overdueReminderMax").value(0))
                .andExpect(jsonPath("$.custom").value(true));
        mvc.perform(get("/internal/circulation/policy").with(token("CirculationPolicyController/get")))
                .andExpect(jsonPath("$.overdueReminderMax").value(0));
    }

    @Test
    void putKhongGuiBaTruongGiuGiaTriDangLuu() throws Exception {
        stored = new CirculationPolicySnapshot(new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 4, 6, 2), 1,
                Instant.EPOCH, null);
        mvc.perform(put("/internal/circulation/policy").with(token("CirculationPolicyController/update"))
                        .contentType(MediaType.APPLICATION_JSON).content("{" + BODY_BASE + ",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueReminderDaysBefore").value(4))
                .andExpect(jsonPath("$.overdueReminderIntervalDays").value(6))
                .andExpect(jsonPath("$.overdueReminderMax").value(2));
    }

    @Test
    void putNgoaiKhoangNhacHanTra422() throws Exception {
        stored = null;
        mvc.perform(put("/internal/circulation/policy").with(token("CirculationPolicyController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + BODY_BASE + ",\"version\":0,\"dueReminderDaysBefore\":31}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CIRCULATION_POLICY"));
        mvc.perform(put("/internal/circulation/policy").with(token("CirculationPolicyController/update"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + BODY_BASE + ",\"version\":0,\"overdueReminderIntervalDays\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void khoaQuyenKhongDoi() throws Exception {
        stored = null;
        mvc.perform(get("/internal/circulation/policy")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/circulation/policy").with(token())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/circulation/policy").with(token("CirculationPolicyController/update")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/circulation/policy").with(token("CirculationPolicyController/get"))
                        .contentType(MediaType.APPLICATION_JSON).content("{" + BODY_BASE + "}"))
                .andExpect(status().isForbidden());
    }
}
