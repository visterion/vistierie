## Project: Vistierie

### Purpose
LLM gateway + subagent framework + scheduler. Acts as a slim provider abstraction layer (currently Anthropic) with tenant isolation, routing, pricing tracking, and audit logging. NOT a workflow engine, MCP server, prompt library, or vector store.

### Tech Stack
- **Language & Build:** Java 25 (via `/usr/local/lib/jdk-25.0.2+10`), Maven, Spring Boot 4.0.6
- **Framework:** Spring Boot Web, Spring Security (crypto only, no auth framework)
- **Database:** PostgreSQL (runtime) + H2 (tests), Flyway migrations
- **Testing:** JUnit5, AssertJ, Testcontainers, WireMock
- **HTTP:** RestClient (Spring Boot native), no RestTemplate

### Code Structure
```
java-server/
├── src/main/java/de/vesterion/vistierie/
│   ├── config/        # RoutingConfig, TenantResolver
│   ├── controller/     # LlmController (POST /llm/complete, POST /llm/vision)
│   ├── model/         # Tenant, ProviderRequest, ProviderResponse, Usage, Audit
│   ├── provider/      # LlmProvider interface, AnthropicProvider, MockProvider (new)
│   ├── pricing/       # Price, Usage (billing aggregation)
│   └── filter/        # BearerTokenFilter (admin + tenant), TenantContextFilter
├── src/main/resources/
│   ├── application.yml # Base config
│   ├── db/migration/   # Flyway migrations (V1__baseline.sql, etc)
└── src/test/java/...  # Unit/integration tests

java-server/mvnw        # Maven wrapper script (use instead of 'mvn')
```

### Key Conventions & Patterns
- **Conditional Beans:** Use `@ConditionalOnProperty` for feature toggles (e.g., mock mode)
- **Naming:** CamelCase classes, lowercase package names (de.vesterion.vistierie)
- **Interfaces:** All providers implement `LlmProvider` contract (name(), complete(), vision())
- **Config:** Properties in `application.yml`, read via `@Value` or `@ConfigurationProperties`
- **Provider Registration:** Each provider is a `@Component` with matching `name()` method; registry looks up by name
- **Tests:** Use `@SpringBootTest` with inline properties for test isolation; disable Flyway/use H2 in tests

### Testing & Verification Commands
```bash
# Set JDK 25 first (required for all Java work)
export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
export PATH=$JAVA_HOME/bin:$PATH

# Run single test
cd /root/vistierie/java-server
./mvnw -Dtest=MockProviderTest test

# Run full suite
./mvnw test

# Build (without tests)
./mvnw clean package -DskipTests
```

### Important Build Setup
- System `java`/`javac` is Java 21; Maven fails with "release version 25 not supported" unless `JAVA_HOME=/usr/local/lib/jdk-25.0.2+10` is set
- Always export `JAVA_HOME` and prepend `$JAVA_HOME/bin` to `PATH` before running `./mvnw`

### Git Workflow
- Branch: `slice-1-llm-gateway`
- All code reviewed before commit; use `git add java-server/` then `git commit -m "..."` 
- Keep CLAUDE.md local (gitignored), never commit it

### Documentation Maintenance Rule
When changing behavior, update corresponding docs in the same commit/PR:
- New REST endpoint → `documentation/api.md`
- Config property changes → `documentation/configuration.md`
- Routing/kill-switch changes → `documentation/routing.md`
- DB schema changes → `documentation/architecture.md` + Flyway migration

All docs live in `documentation/` (not yet created; create when first feature ships).
