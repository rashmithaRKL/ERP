package lk.dexlanka.erp.demo;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Real, file-backed PostgreSQL for loopback-only demonstration. Never used by production. */
@Configuration
@Profile({"demo", "test"})
class DemoDatabase {
    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws Exception {
        var builder = EmbeddedPostgres.builder().setPort(0);
        if (System.getProperty("dexlanka.data") != null) {
            builder.setDataDirectory(System.getProperty("dexlanka.data")).setCleanDataDirectory(false);
        }
        return builder.start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
