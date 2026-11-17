package lms.system.menu.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.DeleteMenuUseCase;
import lms.system.menu.application.GetMyMenuUseCase;
import lms.system.menu.application.InitializeDefaultMenusUseCase;
import lms.system.menu.application.ListMenusUseCase;
import lms.system.menu.application.MenuCachePort;
import lms.system.menu.application.MenuRepositoryPort;
import lms.system.menu.application.UpdateMenuUseCase;
import lms.system.menu.infrastructure.cache.NoopMenuCacheAdapter;
import lms.system.menu.infrastructure.cache.RedisMenuCacheAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Composition root của context menu. Transaction do {@code @Transactional} trên use case. */
@Configuration
public class MenuBeanConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(prefix = "lms.system.menu.cache", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    MenuCachePort redisMenuCache(StringRedisTemplate redis, ObjectMapper mapper,
                                 @Value("${lms.system.menu.cache.ttl:10m}") Duration ttl) {
        return new RedisMenuCacheAdapter(redis, mapper, ttl);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lms.system.menu.cache", name = "enabled", havingValue = "false")
    MenuCachePort noopMenuCache() {
        return new NoopMenuCacheAdapter();
    }

    @Bean
    GetMyMenuUseCase getMyMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache) {
        return new GetMyMenuUseCase(repository, cache);
    }

    @Bean
    ListMenusUseCase listMenusUseCase(MenuRepositoryPort repository) {
        return new ListMenusUseCase(repository);
    }

    @Bean
    CreateMenuUseCase createMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache, Clock clock) {
        return new CreateMenuUseCase(repository, cache, clock);
    }

    @Bean
    UpdateMenuUseCase updateMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache, Clock clock) {
        return new UpdateMenuUseCase(repository, cache, clock);
    }

    @Bean
    InitializeDefaultMenusUseCase initializeDefaultMenusUseCase(MenuRepositoryPort repository, MenuCachePort cache,
                                                                Clock clock) {
        return new InitializeDefaultMenusUseCase(repository, cache, clock);
    }

    @Bean
    DeleteMenuUseCase deleteMenuUseCase(MenuRepositoryPort repository, MenuCachePort cache) {
        return new DeleteMenuUseCase(repository, cache);
    }
}
