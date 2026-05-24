package de.vesterion.vistierie.scheduler;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.agents.AgentRepository;
import de.vesterion.vistierie.routing.RoutingRule;
import de.vesterion.vistierie.routing.RoutingRuleRepository;
import de.vesterion.vistierie.routing.RoutingResolver;
import de.vesterion.vistierie.runs.RunRepository;
import de.vesterion.vistierie.tenants.TenantRepository;
import de.vesterion.vistierie.testsupport.OperationalBudgetFixtures;
import de.vesterion.vistierie.testsupport.StubLlmProvider;
import de.vesterion.vistierie.testsupport.StubLlmScripts;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test-stub-llm")
@TestPropertySource(properties = "vistierie.agents.scheduler.tick-millis=200")
class SchedulerE2ETest extends PostgresTestBase {

    @Autowired AgentRepository agents;
    @Autowired RunRepository runs;
    @Autowired TenantRepository tenants;
    @Autowired StubLlmProvider stub;
    @Autowired ObjectMapper mapper;
    @Autowired RoutingRuleRepository routingRules;
    @Autowired RoutingResolver routingResolver;
    @Autowired JdbcClient jdbc;
    @Autowired OperationalBudgetFixtures budgetFixtures;

    @Test
    void everySecondCronProducesMultipleRunsWithinFiveSeconds() throws Exception {
        var tenantId = UUID.randomUUID();
        var tenantName = "tn-" + tenantId;
        tenants.insert(tenantId, tenantName, "h");
        var now = Instant.now();
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, null,
                "anthropic", "claude-haiku-4-5", 1000, false, false, now, now));
        routingRules.insert(new RoutingRule(UUID.randomUUID(), tenantId, null, "summarize_cell",
                "anthropic", "claude-haiku-4-5", 500, false, false, now, now));
        routingResolver.bumpVersion();

        var script = new ArrayList<StubLlmScripts.ScriptedTurn>();
        for (int i = 0; i < 20; i++) script.add(StubLlmScripts.Turn.endTurn("{\"x\":\"v" + i + "\"}"));
        stub.script(script.toArray(new StubLlmScripts.ScriptedTurn[0]));

        var schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[\"x\"]}");
        var agentId = UUID.randomUUID();
        agents.insert(agentId, tenantId, "every-second", "p", "summarize_cell",
                mapper.createArrayNode(), schema, 3, 30, "wt", false, null);
        budgetFixtures.seed(tenantId, agentId);
        jdbc.sql("UPDATE vistierie.agents SET schedule='* * * * * *' WHERE id=?")
                .param(agentId).update();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                runs.findByTenant(tenantId, 10).size() >= 2);

        var done = runs.findByTenant(tenantId, 10).stream()
                .filter(r -> "cron".equals(r.trigger()))
                .count();
        assertThat(done).isGreaterThanOrEqualTo(2);
    }
}
