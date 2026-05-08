# Configuration

## Properties reference

| Key | Env var | Default | Purpose |
|---|---|---|---|
| `vistierie.admin.token-hash` | `VISTIERIE_ADMIN_TOKEN_HASH` | `""` | bcrypt hash of the admin bearer token |
| `vistierie.anthropic.api-key` | `ANTHROPIC_API_KEY` | `""` | Anthropic API key |
| `vistierie.anthropic.base-url` | — | `https://api.anthropic.com` | Provider HTTP base URL (override for integration tests) |
| `vistierie.anthropic.timeout-seconds` | — | `60` | Request timeout in seconds |
| `vistierie.mock-llm` | `VISTIERIE_MOCK_LLM` | `false` | Swap the real Anthropic provider for `MockProvider` |
| `spring.datasource.url` | `VISTIERIE_DB_URL` | `jdbc:postgresql://localhost:5432/vistierie` | Postgres JDBC URL |
| `spring.datasource.username` | `VISTIERIE_DB_USER` | `vistierie` | Database user |
| `spring.datasource.password` | `VISTIERIE_DB_PASSWORD` | `vistierie` | Database password |
| `server.port` | — | `8090` | HTTP listen port |
| `vistierie.agents.subagent.max-depth` | — | `5` | Maximum subagent recursion depth |
| `vistierie.agents.tool-default-timeout-seconds` | — | `30` | Default per-tool HTTP timeout when the tool def omits `webhook_timeout_seconds` |
| `vistierie.agents.completion-webhook.retry-base-millis` | — | `5000` | Base backoff between completion-webhook retry attempts |

Environment variables take precedence over YAML values via Spring's
`${ENV_VAR:default}` placeholder syntax. In Docker deployments pass secrets as
env vars; do not bake plaintext secrets into images.

---

## Routing config path

```yaml
vistierie:
  routing:
    config-path: classpath:routing.yaml
```

By default routing rules are loaded from `routing.yaml` on the classpath. Point
this at an external file (`file:/etc/vistierie/routing.yaml`) to update routing
without rebuilding the image.

See [routing.md](routing.md) for the rule schema.

---

## Generating the admin token hash

The `VISTIERIE_ADMIN_TOKEN_HASH` value must be a bcrypt hash of the plaintext
admin token (cost factor 10 recommended). Generate one before first deployment.

### Option A — `htpasswd` (available on most Linux systems)

```bash
htpasswd -bnBC 10 "" "your-admin-token" | tr -d ':\n' | sed 's/^.*\$2/\$2/'
```

The command prints a bcrypt hash starting with `$2y$10$…`. Set that value as
`VISTIERIE_ADMIN_TOKEN_HASH`.

### Option B — `jshell` with Spring Security on the classpath

If `htpasswd` is not available and you have a local Maven cache:

```bash
jshell --add-modules ALL-MODULE-PATH \
  --class-path "$(find ~/.m2/repository/org/springframework/security -name 'spring-security-crypto-*.jar' | head -1)" \
  -e 'System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("your-admin-token"));'
```

> **Note:** The `jshell` invocation may need adjustment depending on your local
> Maven cache layout — in particular the path to `spring-security-crypto-*.jar`
> may differ across Spring Boot versions. If it fails, locate the jar manually
> with `find ~/.m2 -name 'spring-security-crypto-*.jar'` and substitute the full
> path.

### Option C — Docker one-liner (no local Java required)

```bash
docker run --rm eclipse-temurin:21 jshell - <<'EOF'
/env --add-modules ALL-MODULE-PATH
System.out.println("token hash goes here -- run htpasswd instead");
EOF
```

In practice Option A (`htpasswd`) is the simplest and most portable choice.
