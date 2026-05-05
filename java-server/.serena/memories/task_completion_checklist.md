# Task Completion Checklist

## For Code Tasks (Features, Bug Fixes, Refactoring)
- [ ] All tests pass (`./mvnw test` or scoped test run)
- [ ] Code follows project conventions (packages, naming, style)
- [ ] Git commit created with meaningful message
- [ ] Related documentation updated (if applicable)
- [ ] No uncommitted changes remain

## TDD Tasks Specifically
1. Write failing test first
2. Run test and confirm failure (RED)
3. Implement minimal code to pass
4. Run test and confirm passing (GREEN)
5. Refactor if needed (without breaking tests)
6. Commit with clear message

## Documentation Updates Needed
Map from `/root/vistierie/CLAUDE.md`:
| Change Type | File |
| --- | --- |
| New REST endpoint | documentation/api.md |
| New provider | documentation/providers.md |
| Config property | documentation/configuration.md |
| Routing changes | documentation/routing.md |
| Webhook changes | documentation/webhooks.md |
| Data model/migration | documentation/architecture.md |
| Deployment step | documentation/operations.md |
| README outdated | README.md |
