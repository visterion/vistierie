package de.vesterion.vistierie;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class PostgresTestBase {

    // Container is started once for the entire test JVM — not per test class.
    // Removing @Container + @Testcontainers avoids the per-class start/stop lifecycle
    // that caused connection failures when multiple test classes share the base.
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withPrivilegedMode(true);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("vistierie.anthropic.api-key", () -> "test-key");
    }
}
