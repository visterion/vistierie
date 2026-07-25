# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

> **This changelog starts with the entries below.** Releases before `1.4.0`
> (`v1.0.0`, `v1.1.0`, `v1.2.0`, `v1.2.1`, `v1.3.0`) were tagged without a
> changelog and are not reconstructed here. For those, see the
> [releases page](https://github.com/visterion/vistierie/releases) and the
> git history.
>
> Note that `java-server/pom.xml` still declares version `1.2.0`, so the built
> artifact is `vistierie-1.2.0.jar` even on newer tags. The git tag is the
> authoritative release marker; the Maven version has not tracked it.

---

## [Unreleased]

Reusability work: making the repository usable by people outside the original
deployment — a Compose stack that runs standalone, a complete environment
reference, a quick start verified end to end, and the standard open-source
project files.

### Added

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

- `docker-compose.yml` is runnable standalone from a clean clone.
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

[Unreleased]: https://github.com/visterion/vistierie/compare/v1.4.1...HEAD
[1.4.1]: https://github.com/visterion/vistierie/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/visterion/vistierie/compare/v1.3.0...v1.4.0
