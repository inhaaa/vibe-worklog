package kr.tna.common.config;

import java.net.URI;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class MigrationRunner {
    private static final String INVALID_URL =
            "명시적인 PostgreSQL JDBC URL이 필요하며 URL 추가 속성은 허용하지 않습니다.";

    private MigrationRunner() { }

    public static FluentConfiguration configuration(Map<String, String> environment) {
        String url = required(environment, "TNA_JDBC_URL");
        String password = required(environment, "TNA_MIGRATION_DB_PASSWORD");
        URI address;
        try {
            address = URI.create(url.startsWith("jdbc:") ? url.substring(5) : url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(INVALID_URL);
        }
        if (!url.startsWith("jdbc:postgresql://") || address.getHost() == null
                || address.getRawPath() == null || address.getRawPath().length() < 2
                || address.getRawUserInfo() != null || address.getRawQuery() != null
                || address.getRawFragment() != null) {
            throw new IllegalArgumentException(INVALID_URL);
        }
        // Flyway 환경값 정규화가 비밀번호의 공백을 바꾸지 않도록 DataSource로 전달한다.
        return Flyway.configure().dataSource(new DriverManagerDataSource(url, "tna_migration", password))
                .locations("classpath:db/migration");
    }

    public static MigrateResult migrate(Map<String, String> environment) {
        return configuration(environment).load().migrate();
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("마이그레이션 실행에 필요한 환경값이 없습니다: " + key);
        }
        return value;
    }
}
