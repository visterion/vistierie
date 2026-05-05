# Vistierie

Vistierie is a slim, tenant-scoped LLM gateway that gives consumer applications
(HiveMem, Draczl, and future tenants) a single, audited, kill-switchable
entry-point into LLM providers. Every call is authenticated with a bearer token,
resolved against a per-tenant routing policy, forwarded to the configured
provider (Anthropic in Slice 1), and recorded in a Postgres audit log with token
counts and micro-EUR cost. Slice 1 ships the gateway only — two synchronous
endpoints (`POST /llm/complete`, `POST /llm/vision`), admin tenant management,
and the kill-switch. The subagent framework, run management, and scheduler are
planned for Slice 2.

See [documentation/architecture.md](documentation/architecture.md) for the
request-flow diagram and data model.

## Documentation

| Document | Contents |
|---|---|
| [architecture.md](documentation/architecture.md) | System overview, data model, request flow |
| [api.md](documentation/api.md) | REST endpoint reference |
| [configuration.md](documentation/configuration.md) | All config properties and env vars |
| [providers.md](documentation/providers.md) | Anthropic plugin, mock mode, adding providers |
| [routing.md](documentation/routing.md) | Routing config schema and resolution rules |
| [operations.md](documentation/operations.md) | Seeding tenants, kill-switch, cost queries, backup order |

## License

Apache-2.0 (planned).
