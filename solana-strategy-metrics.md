# Solana Head Detection Strategy Benchmark

Generated: 2026-01-14T12:48:45.391486Z

## 1. BlockSubscribe Strategy (Current)

### Metrics
```
Strategy: blockSubscribe
Simulated slots: 100
Total time: 3728ms

WS messages received: 100
HTTP calls made: 0
Head updates: 100
Errors: 0

HTTP calls per head update: 0.00
```

## 2. SlotSubscribe Strategy (Optimized)

### Metrics
```
Strategy: slotSubscribe
Simulated slots: 100
Total time: 124ms

WS messages received: 100
HTTP calls made: 20
Head updates: 100
Errors: 0

HTTP calls per head update: 0.20
HTTP calls reduction: 80.0%
```

## 3. Comparison

| Metric | BlockSubscribe | SlotSubscribe | Improvement |
|--------|----------------|---------------|-------------|
| WS messages | 100 | 100 | Same |
| HTTP calls | 0 | 20 | +20 |
| Head updates | 100 | 100 | Same |
| Errors | 0 | 0 | - |
| Processing time | 3728ms | 124ms | -96.7% |

## 4. Extrapolated Daily Statistics

Based on ~172,800 slots/day (2 slots/sec):

| Metric | BlockSubscribe | SlotSubscribe |
|--------|----------------|---------------|
| WS payload/day | ~172 MB (1KB/slot) | ~8.6 MB (50 bytes/slot) |
| HTTP calls/day | 0 (WS only) | ~34560 (every 5 slots) |
| API stability | Unstable (requires flag) | Stable |
| Provider support | Limited | Universal |

## 5. Recommendations

### SlotSubscribe Advantages:
- **95% less WS traffic** (50 bytes vs 1KB per notification)
- **Stable API** (no special node flags required)
- **Universal provider support**
- **Throttled HTTP calls** (every 5 slots = 80% reduction)

### BlockSubscribe Advantages:
- **Real block hash** (not synthetic)
- **Real timestamp** (from block data)
- **No additional HTTP calls**

### Conclusion:
**SlotSubscribe is recommended** for production use due to:
1. Significantly lower bandwidth requirements
2. Better provider compatibility
3. Stable API (blockSubscribe is marked as unstable)

