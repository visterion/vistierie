# Security Policy

This document describes how to report a vulnerability and, just as importantly,
what security properties Vistierie does and does **not** provide today. Read the
[trust boundary](#trust-boundary) section before you deploy it.

---

## Reporting a vulnerability

Report privately via **GitHub private security advisories** on this repository
(Security → Advisories → *Report a vulnerability*).

**Do not open a public issue or pull request for a suspected vulnerability.**

Please include enough detail to reproduce: affected endpoint or component,
configuration, and the observed versus expected behaviour.

---

## Supported versions

Only `main`, and the container image built from it,
[`ghcr.io/visterion/vistierie:main`](https://github.com/visterion/vistierie/pkgs/container/vistierie),
receive security fixes.

There are **no maintained release branches and no LTS line.** Git tags mark
points in history; they are not separately patched. If you are running a tagged
version and a fix lands, the fix will be on `main` only — upgrade rather than
expect a backport.

---

## Trust boundary

**This is the most important section in this document.**

Vistierie v1 is designed to be co-located with its consumer applications on a
single host, communicating over a private Docker network. The trust boundary is
the host, not the individual service. Concretely, as implemented today:

- **There is no TLS between Vistierie and its consumers.** The application
  serves plain HTTP; no `server.ssl.*` configuration is shipped or expected.
- **There is no mTLS and no request signing.** Authentication is a bearer
  token in the `Authorization` header, and nothing else.
- **There is no channel-security enforcement in the auth path.**
  `AuthFilter` (`de.vesterion.vistierie.auth.AuthFilter`) reads the
  `Authorization: Bearer …` header, matches it against the admin token hash for
  `/admin/**` paths or against tenant token hashes otherwise, and passes the
  request on. It does not check `X-Forwarded-Proto`, does not require a secure
  channel, and does not verify the caller's identity by any other means.
  `SecurityConfig` contributes only a `BCryptPasswordEncoder` bean.
- **Tenant tokens therefore travel as plaintext bearer credentials over plain
  HTTP.** Anyone who can observe traffic on the network Vistierie is attached
  to, or who can reach the port, can replay a captured token.

The following paths bypass authentication entirely and are reachable without a
token: `/healthz`, `/readyz`, and everything under `/actuator/`. Treat the
actuator endpoints as internal-only and do not expose them.

### Exposure warning

The Compose stack publishes the API with `"${VISTIERIE_PORT:-8090}:8090"`.
A published Docker port binds on **all host interfaces** by default. Combined
with the points above, that means:

> If the host is reachable from an untrusted network, the API port must not be.
> Bind it to loopback (`127.0.0.1:8090:8090`) and put a reverse proxy in front
> that terminates TLS, or keep the port on a trusted network segment only.

Do not expose Vistierie directly to the public internet as shipped.

---

## Secrets and tokens

**Provider credentials and the admin token hash come from the environment.**
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`,
`AWS_BEARER_TOKEN_BEDROCK`, `VISTIERIE_DB_PASSWORD` and
`VISTIERIE_ADMIN_TOKEN_HASH` are read from environment variables (see
`.env.example` and `documentation/configuration.md`). `.env` is gitignored;
never commit it.

**Only the bcrypt hash of the admin token is stored.** Vistierie never sees the
plaintext admin token at rest: `VISTIERIE_ADMIN_TOKEN_HASH` holds a bcrypt hash
and `AuthFilter` compares the presented token against it with
`BCryptPasswordEncoder.matches`. If the hash is blank, every `/admin/**` request
is rejected with 401.

**Tenant tokens are shown exactly once.** `POST /admin/tenants` generates 24
bytes from `SecureRandom`, hex-encodes them, stores only
`BCryptPasswordEncoder.encode(token)` in `vistierie.tenants.token_hash`, and
returns the plaintext token in that one response. It is not recoverable
afterwards — a lost tenant token means creating a new tenant.

**The admin token is the highest-value secret in the system.** It is not scoped
to a tenant. It authorises tenant creation (including the token that comes with
it), the per-tenant kill switch, budget changes, routing-rule changes, and cost
and audit queries **across all tenants**. Anyone holding it holds the whole
deployment. Rotate it by changing `VISTIERIE_ADMIN_TOKEN_HASH` and restarting.

Note that token verification for tenants iterates over stored tenants and
bcrypt-compares each one; results are memoised in an in-process cache
(`TokenAuthCache`), which is cleared on tenant mutations.

---

## What is audited

Every LLM call writes a row to `vistierie.llm_calls` via `LlmCallRecorder` —
**including calls that failed.** On a `ProviderException`, `LlmService` calls
`recordFailure(...)` before rethrowing, storing status `rate_limited` for
sub-500 status codes and `error` for 5xx, along with the provider's error code.
Unsupported-operation failures are recorded as `error` /
`unsupported_operation`.

Each row carries the call id, tenant, agent, purpose, realm, provider, model,
endpoint, input/output/cache token counts, cost in EUR-micros, duration, status,
error code, run id and batch id. Query it through the admin API
(`GET /admin/llm-calls`) with the admin token.

Request and response bodies are stored separately by `insertWithBody(...)`,
which passes the request through an `ImageRedactor` first. Body retention is
time-bounded and configurable — see
[`documentation/operations.md`](documentation/operations.md) and
[`documentation/configuration.md`](documentation/configuration.md). Assume that
prompts and completions you send through Vistierie are persisted for that
retention window, and size the window accordingly for sensitive realms.
