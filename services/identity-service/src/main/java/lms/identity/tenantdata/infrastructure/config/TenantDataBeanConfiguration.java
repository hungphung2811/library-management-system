package lms.identity.tenantdata.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lms.identity.auth.application.TenantDataTokenIssuerPort;
import lms.identity.tenant.application.ListActiveTenantAdminsQuery;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TenantNameQuery;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase;
import lms.identity.tenantdata.application.ExportPolicy;
import lms.identity.tenantdata.application.ExportSource;
import lms.identity.tenantdata.application.ExportStoragePort;
import lms.identity.tenantdata.application.MaintainTenantDataExportsUseCase;
import lms.identity.tenantdata.application.ManifestCodec;
import lms.identity.tenantdata.application.ManifestSignerPort;
import lms.identity.tenantdata.application.QueryTenantDataExportUseCase;
import lms.identity.tenantdata.application.RequestTenantDataExportUseCase;
import lms.identity.tenantdata.application.RunTenantDataExportUseCase;
import lms.identity.tenantdata.application.TenantAdminsPort;
import lms.identity.tenantdata.application.TenantLookupPort;
import lms.identity.tenantdata.domain.ExportPartRepository;
import lms.identity.tenantdata.domain.TenantDataExportRepository;
import lms.identity.tenantdata.infrastructure.client.HttpExportSource;
import lms.identity.tenantdata.infrastructure.client.TenantContextAdapters;
import lms.identity.tenantdata.infrastructure.persistence.IdentityTenantDataSource;
import lms.identity.tenantdata.infrastructure.scheduling.TenantExportJob;
import lms.identity.tenantdata.infrastructure.security.JacksonManifestCodec;
import lms.identity.tenantdata.infrastructure.security.JwsManifestSigner;
import lms.identity.tenantdata.infrastructure.storage.LocalFileExportStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** Composition root của context tenantdata (adapter persistence tự đăng ký bằng @Repository). */
@Configuration
@EnableConfigurationProperties(TenantDataProperties.class)
public class TenantDataBeanConfiguration {

    /** Nguồn mặc định của gói xuất: nguồn nào không khai base-url thì được ghi "chưa cấu hình" trong manifest. */
    static final List<String> KNOWN_SOURCES = List.of("catalog", "circulation", "sales", "support");
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9]{1,30}");

    @Bean
    ExportPolicy tenantDataExportPolicy(TenantDataProperties p) {
        return new ExportPolicy(p.rateLimit(), p.retention(), p.maxBytes(), p.staleAfter(), p.maxAttempts(),
                p.retryBackoff(), p.tokenTtl());
    }

    @Bean
    ExportStoragePort tenantDataExportStorage(TenantDataProperties p) {
        return new LocalFileExportStorage(Path.of(p.dir()), p.encryptionKey());
    }

    @Bean
    ManifestCodec tenantDataManifestCodec() {
        return new JacksonManifestCodec();
    }

    @Bean
    ManifestSignerPort tenantDataManifestSigner(RSAKey rsaKey) {
        return new JwsManifestSigner(rsaKey);
    }

    @Bean
    TenantLookupPort tenantDataTenantLookup(TenantNameQuery names) {
        return TenantContextAdapters.lookup(names);
    }

    @Bean
    TenantAdminsPort tenantDataAdmins(ListActiveTenantAdminsQuery admins, TransactionalRunner tenantTransactionalRunner) {
        return TenantContextAdapters.admins(admins, tenantTransactionalRunner);
    }

    @Bean
    IdentityTenantDataSource identityTenantDataSource(DataSource dataSource, PlatformTransactionManager txManager) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setFetchSize(IdentityTenantDataSource.FETCH_SIZE);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        return new IdentityTenantDataSource(new NamedParameterJdbcTemplate(jdbc), tx);
    }

    @Bean
    RunTenantDataExportUseCase runTenantDataExportUseCase(TenantDataExportRepository exports,
                                                          ExportPartRepository parts,
                                                          IdentityTenantDataSource identitySource,
                                                          TenantDataProperties props, ExportStoragePort storage,
                                                          TenantLookupPort tenants, TenantAdminsPort admins,
                                                          ExportPolicy policy, ManifestCodec codec,
                                                          ManifestSignerPort signer, PublishPlatformEventPort events,
                                                          TenantDataTokenIssuerPort tokens, Clock clock) {
        List<ExportSource> sources = new ArrayList<>();
        sources.add(identitySource);
        Set<String> notConfigured = new LinkedHashSet<>(KNOWN_SOURCES);
        for (Map.Entry<String, TenantDataProperties.Source> e : new java.util.TreeMap<>(props.sources()).entrySet()) {
            String name = e.getKey();
            String url = e.getValue() == null ? "" : e.getValue().baseUrl();
            if (!SOURCE_NAME.matcher(name).matches()) {
                throw new IllegalStateException("Tên nguồn xuất dữ liệu không hợp lệ: " + name);
            }
            if (url == null || url.isBlank()) {
                notConfigured.add(name);
                continue;
            }
            notConfigured.remove(name);
            sources.add(new HttpExportSource(name, client(url.strip(), props), tokens, policy.tokenTtl()));
        }
        notConfigured.removeAll(sources.stream().map(ExportSource::name).toList());
        return new RunTenantDataExportUseCase(exports, parts, sources, List.copyOf(notConfigured), storage, tenants,
                admins, policy, codec, signer, events, props.appVersion(), clock);
    }

    private static RestClient client(String baseUrl, TenantDataProperties p) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) p.connectTimeout().toMillis());
        // Hợp đồng exporter: 120 giây giữa hai lần nhận dữ liệu; luồng đọc theo khối nên không nạp cả thân vào RAM
        factory.setReadTimeout((int) p.readTimeout().toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    RequestTenantDataExportUseCase requestTenantDataExportUseCase(TenantDataExportRepository exports,
                                                                  ExportStoragePort storage,
                                                                  TenantLookupPort tenants, ExportPolicy policy,
                                                                  PublishPlatformEventPort events, Clock clock) {
        return new RequestTenantDataExportUseCase(exports, storage, tenants, policy, events, clock);
    }

    @Bean
    QueryTenantDataExportUseCase queryTenantDataExportUseCase(TenantDataExportRepository exports,
                                                              ExportPartRepository parts) {
        return new QueryTenantDataExportUseCase(exports, parts);
    }

    @Bean
    DownloadTenantDataExportUseCase downloadTenantDataExportUseCase(TenantDataExportRepository exports,
                                                                    ExportStoragePort storage,
                                                                    PublishPlatformEventPort events, Clock clock) {
        return new DownloadTenantDataExportUseCase(exports, storage, events, clock);
    }

    @Bean
    MaintainTenantDataExportsUseCase maintainTenantDataExportsUseCase(TenantDataExportRepository exports,
                                                                      ExportStoragePort storage, ExportPolicy policy,
                                                                      PublishPlatformEventPort events, Clock clock) {
        return new MaintainTenantDataExportsUseCase(exports, storage, policy, events, clock);
    }

    /** Job và executor chỉ có khi bật ({@code lms.tenant-data.export.enabled}, mặc định bật); test tắt để không chạy nền. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "lms.tenant-data.export.enabled", havingValue = "true", matchIfMissing = true)
    static class JobConfiguration {

        /** Một luồng, hàng đợi 1: tối đa một gói đang xuất và một đang chờ; việc khác chờ chu kỳ sau. */
        @Bean(destroyMethod = "shutdown")
        ThreadPoolExecutor tenantDataExportExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), runnable -> {
                        Thread t = new Thread(runnable, "tenant-data-export");
                        t.setDaemon(true);
                        return t;
                    });
            return executor;
        }

        @Bean
        TenantExportJob tenantExportJob(RunTenantDataExportUseCase run, MaintainTenantDataExportsUseCase maintain,
                                        ThreadPoolExecutor tenantDataExportExecutor, TenantDataProperties props) {
            Executor executor = tenantDataExportExecutor;
            return new TenantExportJob(run, maintain, executor, props.recoverOnStart());
        }
    }
}
