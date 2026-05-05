# Vistierie Java Server Project

## Purpose
LLM gateway and agent framework (Spring Boot 4.0.6, Java 25).

## Tech Stack
- **Build**: Maven with Java 25
- **Framework**: Spring Boot 4.0.6
- **Database**: PostgreSQL with Flyway migrations
- **Testing**: JUnit 5, AssertJ, TestContainers, WireMock, H2 (in-memory)
- **Security**: Spring Security (crypto)

## Code Structure
```
src/main/java/de/vesterion/vistierie/
  ├── auth/          (AuthFilter, AuthExceptions, RequestContext, SecurityConfig)
  ├── tenants/       (Tenant, TenantRepository, AdminTenantController)
  ├── llm/           (LlmController)
  ├── routing/       (RoutingConfig, RoutingResolver, RoutingDecision)
  └── VistierieApplication.java
src/test/java/de/vesterion/vistierie/
  ├── auth/          (AuthFilterTest)
  ├── tenants/       (TenantRepositoryTest, AdminTenantControllerTest)
  ├── routing/       (RoutingResolverTest)
  ├── db/            (MigrationTest)
  └── PostgresTestBase.java
src/main/resources/
  ├── application.yaml
  ├── db/migration/  (Flyway migrations)
```

## Code Conventions
- Package structure: `de.vesterion.vistierie.<domain>`
- Spring Components: `@Component`, `@Repository`, `@Controller`
- Tests: JUnit 5 with AssertJ fluent assertions
- Record types used for data classes (Java 25 feature)
- No constructor-based injection; Spring auto-wiring

## Current Status
- Tasks 1-6 completed (scaffold, migrations, tenants, auth, routing)
- Task 7 in progress: Price table implementation
- Future: Anthropic provider, LLM service, health endpoints, Docker/GHCR, migrations
