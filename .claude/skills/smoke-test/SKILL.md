---
name: smoke-test
description: Use when asked to run a service locally and verify it works end-to-end with real requests ("запусти и проверь", "make sure it actually runs", "проверь что запросы делаются"), after a dependency bump, on a fresh checkout, or after a config change that could affect startup or routing.
---

# Smoke Test a Local Service

## Overview

A smoke test verifies a service actually serves real requests, not just that it compiles. The non-obvious parts: discovering the right probe path, knowing when the service is *truly* ready, and catching silent misconfig in logs.

## When to Use

- "Run it locally and check it works" / "запусти и проверь"
- Verifying a config change didn't break startup
- Validating a fresh checkout / new contributor setup
- After a dependency bump, before relying on a feature

**Don't use for:** unit/integration tests (those are project-scoped); production health checks (use the project's monitoring).

## Workflow

1. **Pick the config.** Prefer existing `demo/`, `examples/`, `testdata/`, or docs configs. If none fits, copy the smallest one and point the upstream/backing service at a **public free endpoint** (no secrets, no creds). Write the config to `/tmp/<project>-test/` — not the repo.
2. **Build the way the project says.** Check `Makefile`, `README`, `CONTRIBUTING`. Multi-module projects often need a foundation/lib build before the main build (e.g. `make build-foundation` then `./gradlew installDist`). `installDist`/`distZip` is usually friendlier than `./gradlew run` because it produces a launchable binary.
3. **Start in background, log to file.** `cmd > run.log 2>&1` with `run_in_background: true`. Do NOT use `./gradlew run` for background — daemon doesn't detach cleanly.
4. **Wait for the LISTEN port, not the "Started" log line.** Many services finish their startup banner but then asynchronously connect to upstreams, register routes, or warm caches. Poll with `lsof -nP -iTCP:<port> -sTCP:LISTEN` OR grep the log for the specific "Listening on" / "Proxy on" lines. Also break the loop on visible errors so you don't hang.
5. **Discover the probe path from the config, not the docs.** Common bite: hitting `/` returns 404 because the proxy defines routed paths (`/eth`, `/api/v1`, etc.). Read the route/path config before probing.
6. **Probe happy + edge.** At minimum: one normal call, one batch (if supported), one invalid input. For JSON-RPC: `chainId`/`blockNumber` + batch + unknown method (expect `-32601`).
7. **Scan the log for soft failures.** `grep -iE "warn|error|exception"` in `run.log`. Things like "method not available, do not detect" are usually fine; "Failed to connect", "Address already in use", "Unauthorized" are not.
8. **Clean up.** Kill by PID on the listen port: `PID=$(lsof -nP -iTCP:<port> -sTCP:LISTEN -t | head -1); kill "$PID"`. Verify the port is free. Don't leave background processes for the user.

## Readiness wait — the right pattern

```bash
# Wait until port is LISTEN or a fatal error appears. Don't sleep blindly.
until lsof -nP -iTCP:8545 -sTCP:LISTEN >/dev/null 2>&1 \
   || grep -qiE "failed to start|address already in use|fatal" run.log; do
  sleep 1
done
```

Use `run_in_background: true` so you can keep doing other work; the harness will notify you.

## JSON-RPC probe template

```bash
curl -s -X POST http://localhost:<port>/<route> \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"<method>","params":[]}'
```

Probe set for an EVM-style endpoint:
- `eth_chainId` → confirms chain identity
- `eth_blockNumber` → confirms upstream is live and synced
- batch `[{...},{...}]` → confirms batching works
- `foo_bar` → expect `error.code: -32601`

## Common Mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Probing `/` instead of `/<route-id>` | 404, empty body | Read the proxy/route config first |
| Treating "Started" as ready | Connection refused / timeout | Wait for `lsof` on the listen port |
| Sleeping a fixed N seconds | Flaky, slow, or premature | Condition-based wait on port + error grep |
| Using `./gradlew run` in background | Hangs daemon, hard to kill | Use `installDist` and run the bin script |
| Config with secrets like `${API_KEY}` | "401 Unauthorized" in log, silent failures | Pick an upstream with no auth, or export the env var |
| Forgetting cleanup | Port stays bound, next run fails | Kill PID by LISTEN port |
| Missing log scan | Service "works" but is degraded | `grep -iE "warn|error"` on the log before declaring success |

## Red Flags

- "Curl returned `{}` so it works" — check the *status code* and the *body shape*, not just non-empty.
- "Log says Started, ship it" — probe an actual endpoint.
- "I'll skip the edge case" — at minimum send one invalid request to confirm error handling is wired.
- "I'll let the process keep running" — always clean up.

## What to Report Back

Concise: what config, which build/run commands, which ports, which probes ran, sample responses, whether you killed the process. Quote one or two log lines that prove the upstream is connected.
