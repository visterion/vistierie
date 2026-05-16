package de.vesterion.vistierie.budget;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.budget.admin.dto.BudgetPatchRequest;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBudgetRepositoryTest extends PostgresTestBase {

    @Autowired TenantRepository tenants;
    @Autowired TenantBudgetRepository repo;

    UUID tenantId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenants.insert(tenantId, "tenant-" + tenantId.toString().substring(0, 8), "tok");
    }

    @Test
    void patchCanSetAndClearBudgetFieldsIndependently() {
        repo.patch(tenantId, new BudgetPatchRequest(1_000L, 5_000L, 80, 90));
        repo.patch(tenantId, new BudgetPatchRequest(null, 6_000L, null, 95));

        BudgetPolicy stored = repo.findByTenantId(tenantId).orElseThrow();

        assertThat(stored.dailyCapMicros()).isNull();
        assertThat(stored.monthlyCapMicros()).isEqualTo(6_000L);
        assertThat(stored.dailyWarnPercent()).isNull();
        assertThat(stored.monthlyWarnPercent()).isEqualTo(95);
        assertThat(stored.operational()).isTrue();
    }
}
