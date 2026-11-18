package lms.system.cms.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lms.cache.NoOpRequestCache;
import lms.cache.RequestCache;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.application.MarkdownRendererPort;
import lms.system.cms.application.PublishedCmsPagesUseCase;
import lms.system.cms.domain.CmsException;
import lms.system.cms.domain.CmsPage;
import lms.system.cms.infrastructure.cache.CachedPublishedCmsPagesUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class CachedPublishedCmsPagesUseCaseTest {

    /** Cache bộ nhớ: lưu JSON qua ObjectMapper của Spring để kiểm serialize/deserialize như Redis thật. */
    static class InMemoryCache implements RequestCache {
        final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        final Map<String, String> store = new HashMap<>();
        int globalCalls;

        @Override
        public <T> T get(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            throw new AssertionError("CMS là dữ liệu nền tảng, phải dùng getGlobal");
        }

        @Override
        public <T> T getGlobal(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            globalCalls++;
            assertThat(ttl).isEqualTo(Duration.ofSeconds(60));
            String k = ns + ":" + key;
            try {
                String json = store.get(k);
                if (json != null) {
                    return mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType()));
                }
                T value = loader.get();
                store.put(k, mapper.writeValueAsString(value));
                return value;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void evict(String namespace, Object key) {
        }

        @Override
        public void evictNamespace(String namespace) {
        }

        @Override
        public void evictGlobalNamespace(String namespace) {
            store.keySet().removeIf(k -> k.startsWith(namespace + ":"));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    CmsPageRepositoryPort repository = mock(CmsPageRepositoryPort.class);
    MarkdownRendererPort renderer = mock(MarkdownRendererPort.class);
    InMemoryCache cache = new InMemoryCache();

    @BeforeEach
    void setUp() {
        CmsPage page = CmsPage.create(UUID.randomUUID(), "gioi-thieu", "Giới thiệu", "# Xin chào", 1, null,
                Instant.parse("2026-01-01T00:00:00Z")).publish(null, Instant.parse("2026-01-02T03:04:05Z"));
        when(repository.findPublishedBySlug("gioi-thieu")).thenReturn(Optional.of(page));
        when(repository.findPublishedBySlug("khong-co")).thenReturn(Optional.empty());
        when(repository.findPublishedSummaries()).thenReturn(
                List.of(new CmsPageRepositoryPort.PublishedSummary("gioi-thieu", "Giới thiệu", 1)));
        when(renderer.toHtml(anyString())).thenReturn("<h1>Xin chào</h1>");
    }

    @Test
    void lanHaiKhongGoiLaiRepositoryVaRender_vaGiaTriSerializeDung() {
        var useCase = new CachedPublishedCmsPagesUseCase(repository, renderer, cache);

        PublishedCmsPagesUseCase.Published first = useCase.get("gioi-thieu");
        PublishedCmsPagesUseCase.Published second = useCase.get("gioi-thieu");
        var list1 = useCase.list();
        var list2 = useCase.list();

        assertThat(second).isEqualTo(first);
        assertThat(second.publishedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(second.bodyHtml()).isEqualTo("<h1>Xin chào</h1>");
        assertThat(list2).isEqualTo(list1).hasSize(1);
        verify(repository, times(1)).findPublishedBySlug("gioi-thieu");
        verify(renderer, times(1)).toHtml(anyString());
        verify(repository, times(1)).findPublishedSummaries();
    }

    @Test
    void khongCacheLoi404() {
        var useCase = new CachedPublishedCmsPagesUseCase(repository, renderer, cache);

        assertThatThrownBy(() -> useCase.get("khong-co")).isInstanceOf(CmsException.class);
        assertThatThrownBy(() -> useCase.get("khong-co")).isInstanceOf(CmsException.class);

        verify(repository, times(2)).findPublishedBySlug("khong-co");
        assertThat(cache.store).isEmpty();
    }

    @Test
    void moiSlugMotKhoa() {
        var useCase = new CachedPublishedCmsPagesUseCase(repository, renderer, cache);
        useCase.get("gioi-thieu");
        assertThat(cache.store).containsOnlyKeys("system.cms-public-page:gioi-thieu");
    }

    @Test
    void ghiXongThiDocCongKhaiThayDuLieuMoi() {
        // repository giả có trạng thái: trang xuất bản hay không
        java.util.concurrent.atomic.AtomicReference<Optional<CmsPage>> published =
                new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
        CmsPage draft = CmsPage.create(UUID.randomUUID(), "gioi-thieu", "Giới thiệu", "# Xin chào", 1, null,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(repository.findPublishedBySlug("gioi-thieu")).thenAnswer(i -> published.get());
        java.util.concurrent.atomic.AtomicReference<CmsPage> latest = new java.util.concurrent.atomic.AtomicReference<>(draft);
        when(repository.findById(draft.id())).thenAnswer(i -> Optional.of(latest.get()));
        when(repository.save(org.mockito.ArgumentMatchers.any(CmsPage.class))).thenAnswer(i -> {
            CmsPage saved = i.getArgument(0);
            latest.set(saved);
            published.set(saved.isPublished() ? Optional.of(saved) : Optional.empty());
            return saved;
        });
        var reader = new CachedPublishedCmsPagesUseCase(repository, renderer, cache);
        var writer = new lms.system.cms.infrastructure.cache.EvictingManageCmsPagesUseCase(repository,
                java.time.Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), java.time.ZoneOffset.UTC), cache);

        assertThatThrownBy(() -> reader.get("gioi-thieu")).isInstanceOf(CmsException.class);
        writer.publish(draft.id(), latest.get().version(), null);
        assertThat(reader.get("gioi-thieu").title()).isEqualTo("Giới thiệu");
        assertThat(reader.get("gioi-thieu")).isNotNull(); // lần hai từ cache
        verify(repository, times(2)).findPublishedBySlug("gioi-thieu");

        writer.unpublish(draft.id(), null);
        assertThatThrownBy(() -> reader.get("gioi-thieu")).isInstanceOf(CmsException.class);
        assertThat(cache.store).isEmpty();
    }

    @Test
    void cacheTatThiHanhViGiongCu() {
        var useCase = new CachedPublishedCmsPagesUseCase(repository, renderer, new NoOpRequestCache());

        useCase.get("gioi-thieu");
        useCase.get("gioi-thieu");
        useCase.list();
        useCase.list();

        verify(repository, times(2)).findPublishedBySlug("gioi-thieu");
        verify(repository, times(2)).findPublishedSummaries();
    }
}
