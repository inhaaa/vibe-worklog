package kr.tna.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import kr.tna.common.tenant.TenantTransactions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class DataSourcesConfiguration {
    @Bean
    @Primary
    HikariDataSource dataSource(Environment environment) {
        return pool(environment, "spring.datasource", "tna-application", 5);
    }

    @Bean
    HikariDataSource authDataSource(Environment environment) {
        return pool(environment, "tna.auth", "tna-authentication", 2);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TenantTransactions tenantTransactions(@Qualifier("dataSource") DataSource dataSource) {
        return new TenantTransactions(dataSource);
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    private HikariDataSource pool(Environment environment, String prefix, String name, int maximumSize) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(environment.getRequiredProperty(prefix + ".url"));
        configuration.setUsername(environment.getRequiredProperty(prefix + ".username"));
        configuration.setPassword(environment.getRequiredProperty(prefix + ".password"));
        configuration.setPoolName(name);
        configuration.setMaximumPoolSize(maximumSize);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5000);
        return new HikariDataSource(configuration);
    }
}
