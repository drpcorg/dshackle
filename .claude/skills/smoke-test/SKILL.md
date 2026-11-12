---
name: smoke-test
description: Use when asked to run dshackle locally and verify it actually serves requests end-to-end via gRPC ("запусти и проверь", "make sure it works"), after a dependency bump, on a fresh checkout, or after a config change that could affect upstream connectivity, the gRPC API, or subscriptions.
---

# Smoke Test dshackle (gRPC)

## Overview

dshackle's primary API is gRPC on port `2449`. A smoke test brings the service up against a real public upstream and exercises four things that a green build alone doesn't prove: reflection serves, the upstream actually connects, a unary call returns a fresh result, and server-streaming subscriptions deliver real data.

The HTTP JSON-RPC proxy is a thin wrapper on top of gRPC and is **not** covered here — verify gRPC and the HTTP path is implied.

## When to Use

- "Run it locally and check it works" / "запусти и проверь"
- Verifying a config-loading or upstream-connector change didn't break startup
- Validating a fresh checkout / new contributor setup
- After a dependency bump (gRPC, Netty, Spring), before relying on the API

**Don't use for:** unit/integration tests (those are project-scoped); production health checks (use the project's monitoring).

## The test config — copy this verbatim

Write to `/tmp/dshackle-test/dshackle.yaml` (outside the repo). This is the *minimum* config that exercises everything: gRPC, real upstream, and subscriptions.

```yaml
version: v1
host: 0.0.0.0
port: 2449
tls:
  enabled: false

monitoring:
  enabled: false

cluster:
  upstreams:
    - id: drpc-eth
      chain: ethereum
      role: primary
      labels:
        provider: drpc
      connection:
        ethereum:
          rpc:
            url: "https://eth.drpc.org"
          ws:
            url: "wss://eth.drpc.org"
```

Why exactly these pieces:
- **No `proxy:` block** — we don't test HTTP, so don't open port 8545.
- **No `auth:` block** — keeps the request flow as simple as possible; if you also need to validate signing, use `demo/response-signing/dshackle.yaml` instead.
- **Both `rpc:` and `ws:`** — `NativeSubscribe` to `newHeads`/`newPendingTransactions` requires a WebSocket upstream. RPC-only will start fine but subscriptions stay empty and the log warns: `"Setting up connector for drpc-eth upstream with RPC-only access, less effective than WS+RPC"`.
- **`https://eth.drpc.org` / `wss://eth.drpc.org`** — public, no API key, used by the response-signing demo, so it's known-good.
- **Ethereum mainnet** — the canonical EVM chain with high block/tx throughput, so subscriptions produce data within seconds.

## Workflow

1. **Build:** `make build-foundation && ./gradlew installDist`. Produces a launchable binary at `build/install/dshackle/bin/dshackle`. Don't use `./gradlew run` — the Gradle daemon doesn't detach cleanly for background use.
2. **Start in background:** `cd /tmp/dshackle-test && rm -f dshackle.log && <repo>/build/install/dshackle/bin/dshackle > dshackle.log 2>&1` with `run_in_background: true`. dshackle picks up `dshackle.yaml` from CWD.
3. **Wait for LISTEN on 2449,** not the "Started" log line. The `Started StarterKt` line appears before the upstream connector finishes initialising:
   ```bash
   until lsof -nP -iTCP:2449 -sTCP:LISTEN >/dev/null 2>&1 \
      || grep -qiE "failed to start|address already in use|fatal" dshackle.log; do
     sleep 1
   done
   ```
   Then verify the WS upstream actually connected: `grep "Connecting to WebSocket" dshackle.log` should appear, no `"WS connection failed"` after it.
4. **Run the probes below** (reflection → Describe → NativeCall → SubscribeNodeStatus → NativeSubscribe).
5. **Scan the log for soft failures:** `grep -iE "warn|error|exception" dshackle.log`. Soft signal you can ignore: `eth_getTdByNumber failed with method is not available, do not detect`. Real failures: `Failed to connect`, `Unauthorized`, `WS connection failed`.
6. **Clean up:** `kill $(lsof -nP -iTCP:2449 -sTCP:LISTEN -t | head -1)`, verify port is free.

## Probe 1 — reflection

```bash
grpcurl -plaintext localhost:2449 list
# expected: emerald.Auth, emerald.Blockchain, grpc.reflection.v1.ServerReflection
```

If this fails or returns nothing, reflection is disabled or gRPC isn't actually serving — everything below will fail too.

## Probe 2 — Describe (unary, health-check)

```bash
grpcurl -plaintext -d '{}' localhost:2449 emerald.Blockchain/Describe
```

Pass criteria:
- `chains[0].chain == "CHAIN_ETHEREUM__MAINNET"`
- `chains[0].status.availability == "AVAIL_OK"`
- `chains[0].currentHeight` is non-zero and within ~10 blocks of mainnet tip
- `chains[0].supportedMethods` is non-empty (≥30 entries typically)
- `chains[0].nodes[0].labels` includes `client_type`, `archive`, `gas-limit` (proves label-detector ran)

## Probe 3 — NativeCall (unary, real upstream hop)

`payload` is `bytes`, holding base64-encoded JSON params.

```bash
PAYLOAD=$(printf '%s' '[]' | base64)        # "W10="
grpcurl -plaintext -d "{
  \"chain\":\"CHAIN_ETHEREUM__MAINNET\",
  \"items\":[{\"id\":1,\"method\":\"eth_blockNumber\",\"payload\":\"$PAYLOAD\"}]
}" localhost:2449 emerald.Blockchain/NativeCall
```

Pass criteria:
- `succeed: true`
- `payload` decodes (`echo <payload> | base64 -d`) to a quoted hex string like `"0x17f6d91"` matching `currentHeight` from Describe
- `upstream_id == "drpc-eth"` (your configured id). A value like `"!all:ETH"` means the response came from cache or quorum aggregator — fine for cacheable methods like `eth_chainId`, but `eth_blockNumber` should hit a real upstream.

## Probe 4 — SubscribeNodeStatus (server stream, ~5 s)

Pushes a node-status snapshot every `timespan` ms. Useful for checking that streaming itself works without requiring upstream WS traffic.

```bash
grpcurl -plaintext -max-time 5 \
  -d '{"timespan":1000}' \
  localhost:2449 emerald.Blockchain/SubscribeNodeStatus
```

Pass criteria:
- At least one `NodeStatusResponse` with `nodeId == "drpc-eth"`, populated `description.nodeLabels` and `description.supportedMethods`
- Ends with `Code: DeadlineExceeded` (that's *us* timing out — expected, not a failure)

## Probe 5 — NativeSubscribe newHeads (server stream, ~15 s)

This is the real subscription test: it forces dshackle to use the WS upstream and proxy live blocks through `NativeSubscribe`.

```bash
PAYLOAD=$(printf '%s' '[]' | base64)
grpcurl -plaintext -max-time 15 \
  -d "{\"chain\":\"CHAIN_ETHEREUM__MAINNET\",\"method\":\"newHeads\",\"payload\":\"$PAYLOAD\"}" \
  localhost:2449 emerald.Blockchain/NativeSubscribe
```

Pass criteria:
- ≥1 `NativeSubscribeReplyItem` within 15 s (Ethereum mainnet produces a block every ~12 s)
- Each `payload` decodes to a JSON block header with `"number": "0x..."` strictly increasing across items
- Final `Code: DeadlineExceeded` is expected — *we* closed the stream

If no items arrive: check `dshackle.log` for WS errors, and confirm the config has the `ws:` block.

**Note:** `Describe.supportedSubscriptions` may not list `newHeads` even though `NativeSubscribe newHeads` works — the detector only enumerates what the upstream advertises via `eth_subscribe` introspection, not what dshackle can actually proxy. Trust the live probe, not the catalogue.

## Common Mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Treating "Started" as ready | `Connection refused` on first probe | Wait for `lsof` on the LISTEN port, then grep for "Connecting to WebSocket" |
| Sleeping a fixed N seconds | Flaky and slow | Condition-based wait on port + error grep (see Workflow §3) |
| Using `./gradlew run` in background | Daemon hangs, hard to kill | Use `installDist` and run the bin script |
| RPC-only config + subscription probe | NativeSubscribe stream stays empty until DeadlineExceeded | Add the `ws:` block; check log for `"Setting up connector ... with RPC-only access"` warning |
| `NativeCall payload` sent as raw JSON | `INVALID_ARGUMENT` from server | base64-encode the JSON params (`payload` is `bytes`) |
| Wrong chain enum (`"ethereum"`) | `invalid value for enum` | Use the enum constant from `common.proto`: `CHAIN_ETHEREUM__MAINNET` |
| No `-max-time` on a subscription probe | grpcurl hangs forever | Always set `-max-time` on `*Subscribe*` calls; `DeadlineExceeded` is the *success* terminator |
| Forgetting cleanup | Port 2449 stays bound, next run fails | `kill $(lsof -nP -iTCP:2449 -sTCP:LISTEN -t)` |
| Trusting `Describe.supportedSubscriptions` | "newHeads not supported" but it actually is | Probe it live with `NativeSubscribe` |

## Red Flags

- "`Describe` returned, ship it" — Describe only proves config loaded; you haven't proved any upstream hop. Run NativeCall.
- "Subscribe returned `DeadlineExceeded`, it's broken" — that's how *you* end the stream. Check whether any items came *before* the deadline.
- "`upstream_id` was `!all:ETH`, that's wrong" — only for `eth_blockNumber`-class methods. For `eth_chainId` it's normal (cacheable).
- "I'll skip subscriptions, unary is enough" — server-streaming uses different code paths (head subscriptions, dedup, multiplexing). Many regressions land only there.
- "I'll let the process keep running" — always clean up.

## What to Report Back

Concise: which build/run commands, port 2449 confirmed listening, results from all 5 probes (key field for each: list count, `availability`, decoded `payload`, count of stream items, head number), and confirmation the process was killed. Quote one or two log lines that prove WS upstream connected.
