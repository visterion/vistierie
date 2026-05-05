package de.vesterion.vistierie.db;

import de.vesterion.vistierie.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationTest extends PostgresTestBase {
    @Autowired JdbcClient jdbc;

    @Test void schemaHasTenantsAndLlmCalls() {
        var tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'vistierie'
                """).query(String.class).list();
        assertThat(tables).contains("tenants", "llm_calls");
    }
}
