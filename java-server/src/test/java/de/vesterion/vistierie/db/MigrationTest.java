package de.vesterion.vistierie.db;

import de.vesterion.vistierie.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test void v3MigrationAddsScheduleColumns() {
        var rows = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='vistierie' AND table_name='agents'
                  AND column_name IN ('schedule','last_tick_at')
                ORDER BY column_name
                """).query(String.class).list();
        assertThat(rows).containsExactly("last_tick_at", "schedule");
    }

    @Test
    void v4MigrationAddsBatchColumns() {
        var llmCols = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='vistierie' AND table_name='llm_calls'
                  AND column_name = 'batch_id'
                """).query(String.class).list();
        assertThat(llmCols).containsExactly("batch_id");

        var runCols = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='vistierie' AND table_name='runs'
                  AND column_name = 'anthropic_batch_id'
                """).query(String.class).list();
        assertThat(runCols).containsExactly("anthropic_batch_id");
    }

    @Test
    void v7MigrationAddsBudgetTablesAndLlmCallAgentColumns() {
        var tables = jdbc.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'vistierie'
                  AND table_name IN ('tenant_budgets', 'agent_budgets')
                ORDER BY table_name
                """).query(String.class).list();
        assertThat(tables).containsExactly("agent_budgets", "tenant_budgets");

        var llmCols = jdbc.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'vistierie'
                  AND table_name = 'llm_calls'
                  AND column_name = 'agent_id'
                """).query(String.class).list();
        assertThat(llmCols).containsExactly("agent_id");
    }

    @Test
    void v7MigrationAddsAgentIndexes() {
        var indexes = jdbc.sql("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'vistierie'
                  AND indexname IN ('llm_calls_agent_time_idx', 'llm_calls_tenant_agent_time_idx')
                ORDER BY indexname
                """).query(String.class).list();
        assertThat(indexes).containsExactly("llm_calls_agent_time_idx", "llm_calls_tenant_agent_time_idx");
    }

    @Test
    void v7WarnPercentConstraintsRejectInvalidValues() {
        var tenantId = UUID.randomUUID();
        var agentId = UUID.randomUUID();
        jdbc.sql("INSERT INTO vistierie.tenants (id, name, token_hash) VALUES (?, ?, ?)")
                .params(tenantId, "tn-" + tenantId, "tok")
                .update();
        jdbc.sql("""
                INSERT INTO vistierie.agents
                  (id, tenant_id, name, system_prompt, model_purpose, tools, webhook_token, paused)
                VALUES (?, ?, ?, ?, ?, '[]'::jsonb, ?, false)
                """)
                .params(agentId, tenantId, "agent-" + agentId, "sys", "purpose", "wt")
                .update();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO vistierie.tenant_budgets (tenant_id, daily_warn_percent)
                VALUES (?, ?)
                """).params(tenantId, 0).update()).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO vistierie.agent_budgets (agent_id, monthly_warn_percent)
                VALUES (?, ?)
                """).params(agentId, 101).update()).isInstanceOf(Exception.class);
    }
}
