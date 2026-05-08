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

    @Test void schemaHasAgentRunTables() {
        var tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'vistierie'
                """).query(String.class).list();
        assertThat(tables).contains("agents", "runs", "run_events");
        var llmCallsHasRunId = jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'vistierie' AND table_name = 'llm_calls' AND column_name = 'run_id'
                """).query(Integer.class).single();
        assertThat(llmCallsHasRunId).isEqualTo(1);
    }
}
