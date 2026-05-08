## Task 10: Mock Provider (Credit-Free Dev/Test)

### Overview
Implement a `MockProvider` that replaces `AnthropicProvider` when `vistierie.mock-llm=true` is set, enabling credit-free development and testing.

### Implementation Steps (TDD)

1. **Create test:** `MockProviderTest.java` (expect FAIL)
2. **Implement:** `MockProvider.java` with `@ConditionalOnProperty(value="vistierie.mock-llm", havingValue="true")`
3. **Update:** `AnthropicProvider.java` - add `@ConditionalOnProperty(value="vistierie.mock-llm", havingValue="false", matchIfMissing=true)` above class declaration
4. **Run test:** Expect PASS
5. **Run full suite:** Verify no regressions
6. **Commit:** `git commit -m "feat(slice1): mock provider for credit-free dev/test"`

### Key Details
- Both providers register as `name() = "anthropic"` — Spring's `@ConditionalOnProperty` ensures only one is in the context at a time
- `matchIfMissing=true` on AnthropicProvider keeps it as the default when property is absent
- Test disables Flyway and uses H2 in-memory DB to avoid Postgres dependency for bean wiring tests
- MockProvider returns fixed usage (42 input, 7 output tokens) and prefixes responses with `[mock]` or `[mock vision]`

### Files to Create/Modify
```
Create: java-server/src/main/java/de/vesterion/vistierie/provider/MockProvider.java
Modify: java-server/src/main/java/de/vesterion/vistierie/provider/AnthropicProvider.java
Create: java-server/src/test/java/de/vesterion/vistierie/provider/MockProviderTest.java
```

### Test Properties
```properties
vistierie.mock-llm=true
vistierie.admin.token-hash=
vistierie.anthropic.api-key=ignored
spring.flyway.enabled=false
spring.datasource.url=jdbc:h2:mem:mock;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
```
