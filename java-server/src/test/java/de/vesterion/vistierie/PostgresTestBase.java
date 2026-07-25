package de.vesterion.vistierie;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Wipes all data before each test class.
     *
     * <p>The container above is shared by the whole JVM, so without this every class
     * inherits the rows of every class that ran before it. That is not merely untidy:
     * background components query across all tenants, so a leftover row keeps acting
     * long after the class that wrote it finished. A stale scheduled agent, for
     * instance, makes {@code AgentScheduler} dispatch runs during unrelated tests,
     * which then drain the shared {@code StubLlmProvider} script queue and fail a
     * test that did nothing wrong.
     *
     * <p>Runs before the class seeds its own fixtures (Spring — and therefore Flyway —
     * has already initialised by the time JUnit invokes {@code @BeforeAll} methods),
     * so each class starts from a known-empty schema. The Flyway history table is
     * preserved; dropping it would re-run every migration on the next context.
     */
    @BeforeAll
    static void wipeSchema() throws SQLException {
        try (var conn = POSTGRES.createConnection("");
             var stmt = conn.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (var rs = stmt.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'vistierie'"
                            + " AND tablename <> 'flyway_schema_history'")) {
                while (rs.next()) tables.add("vistierie." + rs.getString(1));
            }
            // Empty on the very first class, whose context has not migrated yet.
            if (tables.isEmpty()) return;
            stmt.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }
}
