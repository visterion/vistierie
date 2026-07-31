# Contributing to Vistierie

Thanks for considering a contribution. Vistierie is a deliberately small
service with a hard scope boundary — please read
[Scope](#scope--read-this-before-proposing-a-feature) before you start
building anything, so you don't spend effort on a change that will be
declined on principle.

---

## Build and test

The repository is a monorepo with two independently built modules:

| Path | Stack |
|---|---|
| `java-server/` | Java 25 / Spring Boot / PostgreSQL, built with the Maven wrapper |
| `claude-bridge/` | Node 22 / TypeScript, built with npm |

### Prerequisites

- **JDK 25.** Spring Boot 4.1 and the `java.version=25` property in
  `java-server/pom.xml` require it. If your shell's default `java` is older,
  Maven fails on `release 25`; export the JDK explicitly for the build:

  ```bash
  export JAVA_HOME=/path/to/your/jdk-25
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

- **A running Docker daemon.** The test suite is not hermetic: integration and
  end-to-end tests extend `PostgresTestBase`, which starts a
  `postgres:17-alpine` Testcontainer for the test JVM. Without a reachable
  Docker daemon those tests cannot run.
- **Node 22** for `claude-bridge/`.

### Java server

```bash
cd java-server

./mvnw test                  # full suite (needs Docker)
./mvnw -Pstress test         # opt-in concurrency stress group only
./mvnw -DskipTests package   # build the runnable jar
```

`./mvnw -Dtest=ClassNameTest test` runs a single test class.

The `stress` profile reconfigures Surefire to run *only* the `stress` JUnit tag
(`<groups>stress</groups>`), so it is a separate run, not a superset of
`./mvnw test`. Stress tests are excluded from the default suite on purpose —
run them when you touch concurrency, scheduling, or the run executor.

### Node bridge

```bash
cd claude-bridge

npm ci
npm test        # vitest
npm run build   # tsc
```

### What CI runs

`.github/workflows/test.yml` runs `./mvnw -B -ntp verify` for the Java module
(with JaCoCo coverage uploaded to Codecov) and `npm ci && npm test` for
`claude-bridge/`. Run both locally before opening a PR.

---

## Conventions

**Language.** All code, comments, documentation, commit messages, and
identifiers are in English.

**Style.** [Google Java Style](https://google.github.io/styleguide/javaguide.html)
for Java, [Google TypeScript Style](https://google.github.io/styleguide/tsguide.html)
for TypeScript. There is no automated formatter configured in this repo — match
the surrounding file.

**Commits.** [Conventional Commits](https://www.conventionalcommits.org/):
`<type>(<scope>): <description>`, with types `feat`, `fix`, `chore`,
`refactor`, `docs`, `test`, `ci`. One concern per commit.

**Java specifics.**

- Constructor injection only — no field `@Autowired`.
- SLF4J for logging; never `System.out.println`.
- Schema changes go through Flyway migrations in
  `java-server/src/main/resources/db/migration/`. Never edit an already-released
  migration and never change schema by hand.
- Error handling today is per-controller `@ExceptionHandler` methods plus
  `ResponseStatusException` for simple 4xx cases (see `LlmController`,
  `AgentController`, `AdminCostController`). Follow the pattern used by the
  controller you are editing rather than introducing a third mechanism.

**TypeScript specifics.** `strict: true` is already on. Avoid `any` without a
written justification; use typed error classes.

**Tests.** JUnit 5, with Testcontainers-backed integration tests extending
`de.vesterion.vistierie.PostgresTestBase`; vitest for `claude-bridge/`. Write
the failing test first — no production code without a driving test — and run the
module's suite before you consider a change done.

---

## Adding a provider

This is the main extension point.

**If your endpoint speaks the OpenAI `/v1/chat/completions` wire format, you
need no code at all** — declare a block under `vistierie.providers.<name>` in
configuration and point a routing rule at `<name>`. See
[`documentation/providers.md`](documentation/providers.md).

For a genuinely new provider type, add a Spring `@Component` implementing
`de.vesterion.vistierie.provider.LlmProvider`:

```java
public interface LlmProvider {
    String name();
    ProviderResponse complete(ProviderRequest req);
    ProviderResponse vision(String model, int maxTokens, String mediaType,
                            String base64, String prompt);
    // default: throws UnsupportedOperationException
    default ProviderResponse visionMulti(String model, int maxTokens,
                                         List<ImageInput> images, String prompt) { … }
    default BatchSubmission submitBatch(List<BatchItem> items) { … }
    default BatchStatus getBatch(String anthropicBatchId) { … }
    default Stream<BatchResult> streamResults(String resultsUrl) { … }
}
```

- `name()` is the routing string your provider is selected by — it is also the
  registry key, so it must be unique.
- `complete(ProviderRequest)` and `vision(...)` are the only methods you must
  implement. `visionMulti`, `submitBatch`, `getBatch` and `streamResults` have
  defaults that throw `UnsupportedOperationException`, so a provider without
  multi-image or batch support still compiles and still starts.
- Throw `LlmProvider.ProviderException(statusCode, errorCode, msg)` for upstream
  failures. `LlmService` catches it, records an audit row with the right status
  (`rate_limited` for status 429, `error` for everything else) and surfaces the
  error consistently.

**Registration is automatic.** `ProviderRegistry` takes `List<LlmProvider>` in
its constructor and indexes every bean by `name()` at startup — there is no list
to edit. Because it collects into a map keyed by `name()`, two beans returning
the same name will fail startup.

**Never leak provider-specific detail into controllers.** Controllers speak
`ProviderRequest`/`ProviderResponse`; anything vendor-shaped stays behind the
interface.

Document the new provider in
[`documentation/providers.md`](documentation/providers.md) in the same PR:
its name, its configuration properties and env vars, and which capabilities it
does and does not support.

---

## Scope — read this before proposing a feature

Vistierie is a slim LLM gateway, subagent runner and scheduler. Its scope is
defended deliberately, and **a PR that violates it will be declined regardless
of code quality.** That is not a judgement on the work — it is the project's
central design constraint, and we would rather say so here than after you have
written it.

**The two-consumer rule.** Vistierie is built for two consumer applications. A
feature belongs here only if **both** benefit from it. If only one consumer
needs it, it belongs in that consumer, not in the gateway. When you propose a
feature, say explicitly why it is not consumer-specific.

**No consumer domain knowledge.** Vistierie sees only opaque `tenant`, `realm`,
`purpose`, `messages` and `payload`. Prompts, tool implementations and domain
semantics live with the consumer. No consumer's vocabulary or data model may
appear in this codebase.

**Explicit non-goals.** Vistierie is **not** an MCP server, **not** a workflow
engine, **not** a multi-agent bus, **not** a prompt library, and **not** a
vector store. Reasoning lives with the consumer; Vistierie owns the runtime.

If you are unsure whether an idea fits, open an issue describing the use case
from both consumers' point of view before writing code.

---

## Keep the docs in sync in the same PR

Documentation is part of the change, not a follow-up. Never leave documented
behaviour stale.

| You changed… | Update |
|---|---|
| A REST endpoint | [`documentation/api.md`](documentation/api.md) |
| A provider | [`documentation/providers.md`](documentation/providers.md) |
| A config property or env var | [`documentation/configuration.md`](documentation/configuration.md) |
| Routing or the kill switch | [`documentation/routing.md`](documentation/routing.md) |
| The data model | [`documentation/architecture.md`](documentation/architecture.md) **and** a Flyway migration |
| A deployment step | [`documentation/operations.md`](documentation/operations.md) |
| Something in the README's highlights | [`README.md`](README.md) |

---

## Security

Do not report vulnerabilities in a public issue or pull request. See
[SECURITY.md](SECURITY.md).

## License

Contributions are accepted under the [Apache License 2.0](LICENSE).
