# Solana Head Detection Strategy - Real Production Metrics

## Test Configuration
- **Test Duration**: ~11 minutes per strategy
- **Environment**: Real Solana mainnet via DRPC
- **Upstream URL**: wss://lb.drpc.org/solana/...

---

## 1. BLOCK_SUBSCRIBE Strategy (Current)

### Final Metrics
```
Strategy: blockSubscribe
Running time: 11m 3s

TOTALS:
  WS messages received: 1677
  HTTP calls made: 3 (startup only)
  Head updates: 1678
  Errors: 0

RATES (per second):
  WS messages/sec: 2.53
  HTTP calls/sec: 0.00
  Head updates/sec: 2.53

EFFICIENCY:
  HTTP calls per head update: 0.002
  WS messages per head update: 0.999
```

### Characteristics
- **WS payload per message**: ~1KB (full block data with hash, timestamp, etc.)
- **Daily WS traffic estimate**: ~217 MB (1KB × 2.5/sec × 86400 sec)
- **Daily HTTP calls**: ~3 (only startup)
- **API stability**: Unstable (requires `--rpc-pubsub-enable-block-subscription` flag)
- **Provider support**: Limited (premium tier only)

---

## 2. SLOT_SUBSCRIBE Strategy (Optimized)

### Final Metrics
```
Strategy: slotSubscribe
Running time: 11m 22s

TOTALS:
  WS messages received: 1723
  HTTP calls made: 370
  Head updates: 1726
  Errors: 0

RATES (per second):
  WS messages/sec: 2.53
  HTTP calls/sec: 0.54
  Head updates/sec: 2.53

EFFICIENCY:
  HTTP calls per head update: 0.214 (~20%, throttled every 5 slots)
  WS messages per head update: 0.998
```

### Characteristics
- **WS payload per message**: ~50 bytes (only slot/parent/root numbers)
- **Daily WS traffic estimate**: ~10.8 MB (50 bytes × 2.5/sec × 86400 sec)
- **Daily HTTP calls**: ~46,656 (0.54/sec × 86400 sec)
- **API stability**: Stable (no special flags required)
- **Provider support**: Universal

---

## 3. Side-by-Side Comparison

| Metric | BLOCK_SUBSCRIBE | SLOT_SUBSCRIBE | Difference |
|--------|-----------------|----------------|------------|
| **Test duration** | 663 sec | 682 sec | Similar |
| **WS messages** | 1677 | 1723 | Similar |
| **HTTP calls** | 3 | 370 | +367 |
| **Head updates** | 1678 | 1726 | Similar |
| **Errors** | 0 | 0 | Same |
| **WS/sec** | 2.53 | 2.53 | Same |
| **HTTP/sec** | 0.00 | 0.54 | +0.54 |
| **HTTP per head** | 0.002 | 0.214 | +0.212 |

---

## 4. Daily Extrapolation (24 hours)

| Metric | BLOCK_SUBSCRIBE | SLOT_SUBSCRIBE | Winner |
|--------|-----------------|----------------|--------|
| **WS payload/day** | ~217 MB | ~10.8 MB | SLOT (95% less) |
| **HTTP calls/day** | ~3 | ~46,656 | BLOCK |
| **HTTP bandwidth/day** | ~0 | ~4.7 MB* | BLOCK |
| **Total bandwidth/day** | ~217 MB | ~15.5 MB | SLOT (93% less) |
| **API stability** | Unstable | Stable | SLOT |
| **Provider compatibility** | Limited | Universal | SLOT |

*HTTP getBlockHeight response is ~100 bytes

---

## 5. Key Observations

### BLOCK_SUBSCRIBE
- **Pros**: Almost zero HTTP overhead, real block hash and timestamp
- **Cons**: High WS bandwidth, requires special node configuration, limited provider support

### SLOT_SUBSCRIBE
- **Pros**: 95% less WS bandwidth, stable API, universal provider support
- **Cons**: Additional HTTP calls for block height, synthetic hash (from slot)

### Throttling Validation
- Configured: every 5 slots
- Actual HTTP/head ratio: 0.214 (~21.4%)
- Close to expected 20% (1/5) - **throttling works correctly**

---

## 6. Recommendation

**SLOT_SUBSCRIBE is recommended for production** because:

1. **93% less total bandwidth** (15.5 MB vs 217 MB per day)
2. **Stable API** - no special node flags required
3. **Universal provider support** - works with all Solana RPC providers
4. **Identical head update rate** - same real-time performance
5. **Zero errors** in both strategies

The trade-off of ~46K HTTP calls/day is acceptable given:
- Each call is lightweight (~100 bytes response)
- Total HTTP bandwidth is only ~4.7 MB/day
- HTTP calls are throttled (every 5 slots, not every slot)

---

## 7. Files

- BLOCK_SUBSCRIBE metrics: `solana-metrics-block_subscribe.json`, `solana-metrics-block_subscribe.log`
- SLOT_SUBSCRIBE metrics: `solana-metrics-slot_subscribe.json`, `solana-metrics-slot_subscribe.log`

Generated: 2026-01-14
