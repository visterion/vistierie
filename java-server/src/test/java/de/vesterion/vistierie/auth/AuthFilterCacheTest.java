package de.vesterion.vistierie.auth;

import de.vesterion.vistierie.PostgresTestBase;
import de.vesterion.vistierie.tenants.Tenant;
import de.vesterion.vistierie.tenants.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFilterCacheTest extends PostgresTestBase {

    static final String ADMIN_PLAIN = "admin-test-token";
    static final String ADMIN_HEADER = "Bearer " + ADMIN_PLAIN;

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry r) {
        r.add("vistierie.admin.token-hash",
                () -> new BCryptPasswordEncoder().encode(ADMIN_PLAIN));
    }

    @TestConfiguration
    static class CountingRepoConfig {
        @Bean
        @Primary
        CountingTenantRepository countingTenantRepository(JdbcClient jdbc) {
            return new CountingTenantRepository(jdbc);
        }
    }

    static class CountingTenantRepository extends TenantRepository {
        // Static, not instance: Spring wraps @Repository beans (annotation is found via
        // superclass lookup even for a subclass) in a CGLIB exception-translation proxy
        // built via Objenesis, which skips constructors/instance-field initializers on the
        // proxy object. A static counter is shared with the real target instance regardless.
        static final AtomicInteger FIND_ALL_CALLS = new AtomicInteger();

        CountingTenantRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public List<Tenant> findAll() {
            FIND_ALL_CALLS.incrementAndGet();
            return super.findAll();
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired CountingTenantRepository tenants;
    @Autowired BCryptPasswordEncoder enc;
    @Autowired AuthFilter authFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilter(authFilter)
                .build();
        CountingTenantRepository.FIND_ALL_CALLS.set(0);
    }

    @Test void secondRequestWithSameTokenHitsCacheNotBcryptScan() throws Exception {
        String uniqueName = "hivemem-" + UUID.randomUUID();
        String plainToken = "token-" + UUID.randomUUID();
        tenants.insert(UUID.randomUUID(), uniqueName, enc.encode(plainToken));

        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
        int afterFirst = CountingTenantRepository.FIND_ALL_CALLS.get();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
        int afterSecond = CountingTenantRepository.FIND_ALL_CALLS.get();

        // Second request with the same token must be served from cache: no additional
        // findAll()/BCrypt scan.
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test void adminMutationFlushesCacheSoSubsequentRequestRescans() throws Exception {
        String uniqueName = "dracul-" + UUID.randomUUID();
        String plainToken = "token-" + UUID.randomUUID();
        tenants.insert(UUID.randomUUID(), uniqueName, enc.encode(plainToken));

        // Populate the cache.
        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
        int afterFirst = CountingTenantRepository.FIND_ALL_CALLS.get();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1);

        // Confirm cache is actually being used before the mutation.
        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
        assertThat(CountingTenantRepository.FIND_ALL_CALLS.get()).isEqualTo(afterFirst);

        // Admin mutation (kill) must flush the cache.
        mvc.perform(post("/admin/tenants/" + uniqueName + "/kill")
                        .header("Authorization", ADMIN_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test\"}"))
                .andExpect(status().isNoContent());

        // Same token again: must re-scan (cache was flushed), not silently keep serving
        // the previously cached principal.
        mvc.perform(get("/llm/complete").header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isMethodNotAllowed());
        assertThat(CountingTenantRepository.FIND_ALL_CALLS.get()).isGreaterThan(afterFirst);
    }
}
