package lms.database.autoconfigure;

import lms.database.api.ConnectionSpec;
import lms.database.config.DatabaseProperties;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Với mỗi connection quan hệ đang bật, đăng ký bean tên cố định (orderDbDataSource,
 * orderDbEntityManagerFactory, orderDbTransactionManager) để dùng với @Transactional("...") và
 * @EnableJpaRepositories(entityManagerFactoryRef=..., transactionManagerRef=...). Bean lấy từ registry
 * (lazy theo FactoryBean) và phụ thuộc DatabaseLifecycleManager nên luôn có connection đã mở.
 */
public class DatabaseBeanRegistrar implements BeanDefinitionRegistryPostProcessor {

    private final Environment environment;

    public DatabaseBeanRegistrar(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        DatabaseProperties properties = Binder.get(environment).bind("database", DatabaseProperties.class)
                .orElseGet(DatabaseProperties::new);
        for (Map.Entry<String, ConnectionSpec> entry : properties.getConnections().entrySet()) {
            ConnectionSpec spec = entry.getValue();
            if (spec == null || !spec.isEnabled() || spec.getType() == null || !spec.getType().isRelational()) {
                continue;
            }
            String name = entry.getKey().trim().toUpperCase(Locale.ROOT);
            define(registry, BeanNames.dataSource(name), RelationalConnectionFactoryBeans.DataSourceBean.class, name);
            define(registry, BeanNames.transactionManager(name),
                    RelationalConnectionFactoryBeans.TransactionManagerBean.class, name);
            if (spec.getEntityPackages() != null && !spec.getEntityPackages().isEmpty()) {
                define(registry, BeanNames.entityManagerFactory(name),
                        RelationalConnectionFactoryBeans.EntityManagerFactoryBean.class, name);
            }
        }
    }

    private void define(BeanDefinitionRegistry registry, String beanName, Class<?> type, String connectionName) {
        if (registry.containsBeanDefinition(beanName)) {
            return; // Service tự khai báo bean cùng tên thì giữ nguyên.
        }
        BeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(type)
                .addConstructorArgReference(DatabaseAutoConfiguration.REGISTRY_BEAN)
                .addConstructorArgValue(connectionName)
                .addDependsOn(DatabaseAutoConfiguration.LIFECYCLE_BEAN)
                .getBeanDefinition();
        registry.registerBeanDefinition(beanName, definition);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Không cần xử lý.
    }
}
