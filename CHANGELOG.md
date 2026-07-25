# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

> **This changelog starts with the entries below.** Releases before `1.4.0`
> (`v1.0.0`, `v1.1.0`, `v1.2.0`, `v1.2.1`, `v1.3.0`) were tagged without a
> changelog and are not reconstructed here. For those, see the
> [releases page](https://github.com/visterion/vistierie/releases) and the
> git history.

---

## [1.5.0] - 2026-07-25

The first release since `v1.4.1`, and a large one: it carries everything that
accumulated on `main` in the meantime. Headline items are the
**Claude subscription provider** (with its `claude-bridge` sidecar, tool use,
routing fallback and exhaustion cooldown), **MCP tools for agents**, and the
reusability work that makes the repository runnable from a clean clone.

> **Upgrade note — Compose.** The base `docker-compose.yml` no longer joins
> `hivemem-net`. Deployments that co-locate Vistierie with its consumers must
> now add the overlay explicitly:
> `docker compose -f docker-compose.yml -f docker-compose.consumers.yml up -d`.
> Without it, the next `up -d` leaves Vistierie unreachable for its consumers.

### Added

- **`claude-subscription` provider.** Routes completions through a Claude Max
  subscription via the new `claude-bridge` sidecar (Claude Agent SDK) instead of
  a metered API key. Ships as a Compose service behind the `subscription`
  profile. Subscription calls are booked at zero real cost with the equivalent
  API price recorded as shadow cost, so cost reporting stays comparable.
- **Routing fallback.** Routing rules carry a fallback provider/model (V12); a
  failed primary call is retried once against the fallback. Exposed on the admin
  routing API.
- **Tool use over the subscription.** The bridge keeps sessions (start /
  continue / replay) so agent runs with tools work through
  `claude-subscription`, with session threading across turns.
- **Subscription exhaustion cooldown.** A `subscription_exhausted` 429 (daily or
  weekly limit) opens a global cooldown — default one hour, configurable via
  `vistierie.claude-subscription.cooldown-seconds` — during which calls skip
  straight to the fallback provider instead of re-attempting and failing.
- **MCP tools for agents.** Agent tool definitions accept an `mcp` tool type
  with per-agent `mcp_credentials` (V13), a cached MCP client with retry, and
  validation that credentials and tool declarations agree.
- **Routing `effort`.** Routing rules carry an `effort` field (V14) that is
  forwarded to the bridge, alongside `max_tokens` passthrough.
- **`GET /runs` pagination.** Accepts `limit`, `offset`, `from` and `to`. The
  response is unchanged for callers that pass nothing: the 100 newest runs, as a
  bare JSON array.
- **Cost breakdown by agent.** `group_by=agent` on the admin cost API, with an
  `(unattributed)` bucket for calls without an agent.
- `.env.example` covering every configurable environment variable, with the
  required/optional split and links into `documentation/configuration.md`.
- `docker-compose.consumers.yml` as a separate overlay, so the base stack no
  longer presumes the consumer services are present.
- `CONTRIBUTING.md` — build and test instructions, coding conventions, how to
  add a provider, the project's scope rules, and the docs-in-the-same-PR table.
- `SECURITY.md` — vulnerability reporting, supported versions, the v1 trust
  boundary (no TLS/mTLS/request signing between services), secret handling, and
  what the audit trail records.
- This changelog.

- `.env.example` covering every configurable environment variable, with the
  required/optional split and links into `documentation/configuration.md`.
- `docker-compose.consumers.yml` as a separate overlay, so the base stack no
  longer presumes the consumer services are present.
- `CONTRIBUTING.md` — build and test instructions, coding conventions, how to
  add a provider, the project's scope rules, and the docs-in-the-same-PR table.
- `SECURITY.md` — vulnerability reporting, supported versions, the v1 trust
  boundary (no TLS/mTLS/request signing between services), secret handling, and
  what the audit trail records.
- This changelog.

### Changed

- **The Maven project version tracks the release tag again.** `pom.xml` had been
  stuck at `1.2.0` since the `v1.2.0` tag, so `v1.2.1`, `v1.3.0`, `v1.4.0` and
  `v1.4.1` all built an artifact named `vistierie-1.2.0.jar` while the tag said
  otherwise. From here the jar is `vistierie-1.5.0.jar` and the two agree.
- `docker-compose.yml` no longer joins `hivemem-net` and is runnable standalone
  from a clean clone; consumer connectivity moved to
  `docker-compose.consumers.yml` (see the upgrade note above).
- Per-call log line for every gateway completion and every agent-run turn.
- README quick start rewritten from an actual end-to-end run against
  `ghcr.io/visterion/vistierie:main`, using `VISTIERIE_MOCK_LLM=true` so it
  needs no provider credentials, plus a "what do you want to do" signpost into
  `documentation/`.
- `documentation/operations.md` now documents `.env` as the setup path, leads
  with the containerised `htpasswd` recipe for generating the admin bcrypt hash,
  and its Compose and LXC sections match the shipped stacks.
- `documentation/configuration.md` closes the gaps between documented and
  actually-read configuration.

### Fixed

- **Streaming sessions: one open session per agent is now enforced by the
  database** (V15, applied automatically on upgrade — it replaces the existing
  partial index on `streaming_sessions(agent_id) WHERE status = 'open'` with a
  unique one). Two concurrent ticks could previously both open a session for the
  same agent, after which every lookup for that agent failed until a row was
  closed by hand. Uniqueness is limited to open sessions, so an agent can still
  run any number of sessions over time. Upgrading with two open rows already
  present for one agent will fail the migration; close one first.
- Budget: the per-call reservation is held in-process, closing a
  time-of-check/time-of-use hole that let concurrent calls exceed a cap. Audit
  writes are non-fatal.
- Budget: agent usage is reported even for agents without a budget policy.
- Auth: tenant token lookups are cached, and admin-gate checks use the decoded
  request path.
- Providers: configured HTTP timeouts are actually applied to the Anthropic and
  OpenAI-compatible clients; the bridge times out and aborts SDK queries.
- Runner: subagent depth and `max_run_seconds` are enforced across the async
  boundary; output extraction is schema-aware and no longer picks up stray JSON
  in prose; payload-less runs send a non-blank first user message.
- Runs: closed a register/read race in the `GET /runs/{id}` long poll.
- Streaming: a failed `EventSourcePoller.poll()` no longer advances the cursor,
  so events are not skipped.
- Compose now forwards every documented environment variable into the
  containers; previously some were documented but never passed through.
- Documented that `$`-bearing values in `.env` (bcrypt hashes, generated
  passwords) must be single-quoted, because Compose otherwise interpolates them
  and silently delivers a truncated value.
- Corrected the privileged-service count in the LXC section of
  `documentation/operations.md`.

### Removed

- Local editor/agent tooling notes that had been committed under
  `java-server/.serena/`.

### Internal

- Test isolation: the shared Postgres test container is wiped per test class and
  the scheduler tick is pushed out of the way, so leftover rows from one class no
  longer trigger work during another. The LLM stub now fails loudly when its
  script is exhausted instead of returning an empty response that looked like a
  real one. No production behaviour changes; this removes a class of CI-only
  flake that was hard to attribute.

---

## [1.4.1] - 2026-06-20

### Changed

- Audit: default retention for stored LLM request/response bodies raised from 7
  to 30 days (`vistierie.audit.body-retention-days`). Only bodies age out; the
  `llm_calls` metadata and cost row is retained regardless. The data-retention
  model is now documented.
- README Spring Boot badge corrected to 4.1 to match the declared dependency.

---

## [1.4.0] - 2026-06-20

### Added

- **Run transcripts.** Provider-neutral transcript reader with
  `digest`/`compact`/`full` views, backed by a new schema (V11:
  `response_content_json`, `run_tool_calls`, `run_search_doc`). LLM response
  content blocks are persisted per call, and tool calls are captured per turn
  with input, output and error.
- **Run search.** A Postgres full-text index is built on run completion, exposed
  as a tenant-scoped search API with snippet results and an admin variant that
  can search across tenants.
- Agents expose a computed `next_run_at` on the agent detail response, backed by
  a `CronSchedules.nextRunAt` helper that resolves the next UTC fire time from a
  cron expression.

### Fixed

- Bedrock: the socket read timeout is configurable (default 180 s), so long
  calls no longer fail with a read timeout.
- Agents: per-agent `max_tokens` with an 8192 default.
- Runner: a run is marked failed on any uncaught exception instead of being left
  stuck in `running`; hitting `max_tokens` or producing no tool use now fails
  cleanly rather than returning an empty message.
- Dispatcher: agent execution runs genuinely asynchronously via a dedicated
  bean.
- Transcripts: tool-call capture is best-effort and can never fail a run.

[1.5.0]: https://github.com/visterion/vistierie/compare/v1.4.1...v1.5.0
[1.4.1]: https://github.com/visterion/vistierie/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/visterion/vistierie/compare/v1.3.0...v1.4.0
