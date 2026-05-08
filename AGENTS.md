# AGENTS.md — Vistierie

Notes for any agent (human or LLM) working in this repo. The goal is to avoid repeating discoveries from previous sessions.

## Build toolchain

- JDK 25 lives at `/usr/local/lib/jdk-25.0.2+10`. The system `java` is older — Maven will fail with *"release version 25 not supported"* unless `JAVA_HOME` is exported.
- Always run Maven via:
  ```bash
  export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
  export PATH=$JAVA_HOME/bin:$PATH
  cd java-server && ./mvnw <goals>
  ```
- Spring Boot **4.0.6** on JDK 25. Notable Boot-4 reality:
  - Jackson migrated to **`tools.jackson.*`** (Jackson 3). When you autowire `ObjectMapper` or import `JsonNode`, use `tools.jackson.databind.*`, not `com.fasterxml.jackson.databind.*`.
  - `RestClient.builder().build()` defaults to the JDK `HttpClient` with HTTP/2. WireMock 3.x speaks HTTP/1.1 only and the JDK client returns *RST_STREAM cancelled* / *EOF reached*. Always inject `new SimpleClientHttpRequestFactory()` into a `RestClient.Builder` that has to talk to WireMock or any HTTP/1.1-only server in tests:
    ```java
    RestClient.builder().baseUrl(url).requestFactory(new SimpleClientHttpRequestFactory()).build();
    ```
  - MockMvc tests don't auto-pick up `@Component` filters via `@AutoConfigureMockMvc` here. Use `MockMvcBuilders.webAppContextSetup(wac).addFilter(authFilter).build()` in `@BeforeEach`.

## Tests

- Postgres testcontainer is started **once per JVM** in `PostgresTestBase` via a `static {}` block (no `@Testcontainers` / `@Container`). The container is shared across all subclassing tests — write tests that tolerate residual rows or use UUID-suffixed identifiers / `TRUNCATE` in `@BeforeEach`.
- This host's AppArmor blocks the unprivileged Postgres container. `PostgresTestBase` uses `withPrivilegedMode(true)`. CI doesn't have AppArmor and runs the same flag without issue.
- Run a single test class: `./mvnw -Dtest=<ClassName> test`.
- Run a single method: `./mvnw -Dtest=<ClassName>#<method> test`.

## GitHub remotes

- **Vistierie repo:** `https://github.com/visterion/vistierie.git` (the org spelling is **`visterion`**, not `vesterion`).
- **HiveMem repo:** `https://github.com/visterion/HiveMem.git` (capital `H`).
- Both repos are pushable via the `gh` CLI's stored PAT — run `gh auth setup-git` once per machine to install the credential helper. After that, `git push -u origin <branch>` Just Works.
- If `git push` returns *"could not read Username for 'https://github.com'"*, the credential helper isn't installed for this repo. Either run `gh auth setup-git` or set the remote URL with the token inline (HiveMem's clone uses an inline-token URL — Vistierie's does not).

## CI

- `.github/workflows/docker.yml` triggers on push to `main`, push to `slice-*` branches, and on PRs. PRs build but don't push to GHCR.
- Image tags: `:main` for main, `:<branch>` for slice branches, `:pr-<n>` for PRs. All under `ghcr.io/visterion/vistierie`.
- The workflow is wired against `${{ github.actor }}` + `${{ secrets.GITHUB_TOKEN }}` — no extra secrets required.
- Watching a run: `gh run list --repo visterion/vistierie` then `gh run watch <id> --repo visterion/vistierie --exit-status`.

## Branch & PR conventions

- Slice branches: `slice-<n>-<short-name>` (e.g. `slice-1-llm-gateway`).
- HiveMem-side branches that consume a Vistierie slice: `slice<n>-vistierie-<feature>` (e.g. `slice1-vistierie-summarizer`).
- Commit prefixes: `feat(slice<n>):`, `chore(slice<n>):`, `test(slice<n>):`, `docs(slice<n>):`, `ci(slice<n>):`, `refactor(<area>):` (HiveMem-side migrations).
- Don't commit `docs/` (gitignored — local design notes only). Operator-facing docs live in `documentation/`.
- Don't commit `CLAUDE.md` (gitignored — operator-local notes).

## Quick checklist before pushing

1. `./mvnw test` — full suite green.
2. `git status` — no stray spec/plan files (those live under `docs/` and are gitignored).
3. `git push -u origin <branch>` — first push of a branch needs `-u`.
4. `gh run list --repo visterion/vistierie --limit 3` — confirm CI picked it up.
