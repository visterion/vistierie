# Vistierie — Project Notes for Claude

## Build toolchain

- Java 25 is installed at `/usr/local/lib/jdk-25.0.2+10`. System `java`/`javac` on this host is older (21), so Maven fails with "release version 25 not supported" unless `JAVA_HOME` is set.
- Run Java builds/tests with:
  ```
  export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
  export PATH=$JAVA_HOME/bin:$PATH
  cd java-server && ./mvnw -Dtest=<TestName> test
  ```
- Backend lives in `java-server/` (Spring Boot, JDK 25, Flyway). Mirrors HiveMem's layout for ops consistency.

## Deployment

- Production image will be `ghcr.io/visterion/vistierie:main`, built by `.github/workflows/docker.yml` on pushes to `main` (workflow not yet present — add when first deployable build lands).
- v1 deployment topology: co-located with consumers (HiveMem, Draczl) on the same host via private Docker network. No TLS / mTLS / HMAC between services in v1 — see spec §8.1 Trust Boundary.

## Scope discipline

Vistierie is a **slim LLM gateway + subagent framework + scheduler**. It is **not**:
- an MCP server
- a workflow engine
- a multi-agent bus
- a prompt library / prompt store
- a vector store

Prompts live with the consumer. Vistierie sees only opaque `tenant`, `realm`, `purpose`, `messages`, `payload`. **Never** add domain knowledge about HiveMem cells/tunnels/Bees or Draczl prey-types into Vistierie code.

The two-tenant rule: HiveMem + Draczl must both benefit from any new feature. If only one of them needs it, it does not belong in Vistierie.

## Documentation maintenance

`documentation/` at the repo root will hold operator-facing docs (not yet present — create when first feature ships). It must stay in sync with the code.

**When implementing a feature or change, check and update the relevant page in the same branch/PR:**

| What changed | Update this file |
|---|---|
| New REST endpoint added or removed | `documentation/api.md` |
| New provider plugin (Anthropic, OpenAI, Ollama, …) | `documentation/providers.md` |
| Config property added/changed | `documentation/configuration.md` |
| Routing rules / kill-switch behavior changed | `documentation/routing.md` |
| Webhook contract changed | `documentation/webhooks.md` |
| Data model changed (tables under `vistierie` schema) | `documentation/architecture.md` + Flyway migration |
| New deployment step | `documentation/operations.md` |
| Root README highlights outdated | `README.md` |

The rule: **if you change behavior that is documented, update the doc in the same commit or PR. Never leave documentation stale.**

## Design specs

- Detailed design specs live in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.
- **`docs/` is gitignored** — specs are local-only working notes, never committed and never on GitHub.
- GitHub issues are self-contained trackers/plans. They must not link to spec docs (the docs aren't public) and must not contain forward references to anything that doesn't yet exist in the issue body itself.
- Code (migrations, source, tests) is committed normally; only the prose design history stays local.
- Current authoritative spec: `docs/superpowers/specs/2026-05-03-vistierie-standalone-design.md`.
