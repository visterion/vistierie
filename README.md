# Vistierie

[![docker](https://github.com/visterion/vistierie/actions/workflows/docker.yml/badge.svg)](https://github.com/visterion/vistierie/actions/workflows/docker.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Vistierie is a slim, tenant-scoped LLM gateway. Consumer applications
(HiveMem, Draczl, future tenants) hand it a `purpose`, `realm`, and a
`messages` array; Vistierie authenticates the caller, checks the kill
switch, resolves the per-tenant routing policy to a concrete provider
and model, forwards the call, and writes a Postgres audit row with
exact token counts and micro-EUR cost.

The longer-term vision adds an in-process **agent + subagent
framework** (context-shielded subagents whose intermediate turns never
leak into the parent's context window) and a **cron scheduler** for
recurring agent runs. Slice 1 — what's currently on `main` — ships only
the synchronous gateway.

## What's in Slice 1

- `POST /llm/complete` and `POST /llm/vision` — Anthropic-backed.
- Tenant + admin bearer-token auth (bcrypt-stored).
- YAML-driven routing (`<tenant, purpose>` → `<provider, model>`).
- Per-call audit (`vistierie.llm_calls`) with EUR-micros cost.
- Per-tenant kill switch — checked before every dispatch.
- Mock-LLM mode for credit-free dev/CI.
- Container image at `ghcr.io/visterion/vistierie:main`.

## Quick start

```bash
docker run --rm -p 8090:8090 \
  -e VISTIERIE_DB_URL=jdbc:postgresql://host.docker.internal:5432/vistierie \
  -e VISTIERIE_DB_USER=vistierie \
  -e VISTIERIE_DB_PASSWORD=vistierie \
  -e VISTIERIE_ADMIN_TOKEN_HASH='<bcrypt-hash>' \
  -e ANTHROPIC_API_KEY='sk-ant-...' \
  ghcr.io/visterion/vistierie:main
```

For local development with everything wired up:

```bash
cd java-server
docker compose -f docker-compose.dev.yml up --build
```

See [documentation/operations.md](documentation/operations.md) for
seeding tenants, generating bcrypt hashes, and running cost rollup
queries.

## Documentation

| Document | Contents |
|---|---|
| [architecture.md](documentation/architecture.md) | System overview, data model, request flow |
| [api.md](documentation/api.md) | REST endpoint reference |
| [configuration.md](documentation/configuration.md) | Config properties and environment variables |
| [providers.md](documentation/providers.md) | Anthropic plugin, mock mode, adding providers |
| [routing.md](documentation/routing.md) | Routing config schema and resolution rules |
| [operations.md](documentation/operations.md) | Tenants, kill switch, cost queries, backups |
| [AGENTS.md](AGENTS.md) | Repo conventions for human and AI contributors |

## Build from source

Requires JDK 25 and Docker (for the Postgres testcontainer used in tests).

```bash
export JAVA_HOME=/path/to/jdk-25
cd java-server
./mvnw test          # full suite (~30 s)
./mvnw -DskipTests package
java -jar target/vistierie-0.1.0-SNAPSHOT.jar
```

## Roadmap

- **Slice 1 (current):** synchronous LLM gateway. *Done.*
- **Slice 2:** agent runtime — registered agents, tool-use loop with
  consumer-hosted tool webhooks, context-shielded subagents.
- **Slice 3:** cron scheduler for recurring agent runs.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
