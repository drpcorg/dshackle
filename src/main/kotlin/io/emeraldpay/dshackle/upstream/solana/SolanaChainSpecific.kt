package io.emeraldpay.dshackle.upstream.solana

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.DefaultSolanaMethods
import io.emeraldpay.dshackle.upstream.EgressSubscription
import io.emeraldpay.dshackle.upstream.GenericSingleCallValidator
import io.emeraldpay.dshackle.upstream.IngressSubscription
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.generic.AbstractChainSpecific
import io.emeraldpay.dshackle.upstream.generic.GenericEgressSubscription
import io.emeraldpay.dshackle.upstream.generic.GenericIngressSubscription
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ============================================================================
// METRICS
// ============================================================================

/**
 * Metrics collector for comparing head detection strategies
 */
class SolanaHeadMetrics(val strategyName: String) {
    private val log = LoggerFactory.getLogger(SolanaHeadMetrics::class.java)

    // Counters
    val wsMessagesReceived = AtomicLong(0)
    val httpCallsMade = AtomicLong(0)
    val headUpdates = AtomicLong(0)
    val errors = AtomicLong(0)

    // Latency tracking
    private val latencies = ConcurrentHashMap<String, MutableList<Long>>()

    // Timestamps
    @Volatile var startTime: Instant = Instant.now()
    @Volatile var lastHeadUpdate: Instant = Instant.now()

    fun recordWsMessage() {
        wsMessagesReceived.incrementAndGet()
    }

    fun recordHttpCall() {
        httpCallsMade.incrementAndGet()
    }

    fun recordHeadUpdate() {
        headUpdates.incrementAndGet()
        lastHeadUpdate = Instant.now()
    }

    fun recordError() {
        errors.incrementAndGet()
    }

    fun recordLatency(operation: String, durationMs: Long) {
        latencies.computeIfAbsent(operation) { mutableListOf() }.add(durationMs)
    }

    fun reset() {
        wsMessagesReceived.set(0)
        httpCallsMade.set(0)
        headUpdates.set(0)
        errors.set(0)
        latencies.clear()
        startTime = Instant.now()
    }

    fun logSummary() {
        val runningTime = Duration.between(startTime, Instant.now())
        val runningSeconds = runningTime.seconds.coerceAtLeast(1)

        log.info(
            """
            |
            |========== SOLANA HEAD METRICS: $strategyName ==========
            |Running time: ${runningTime.toMinutes()}m ${runningTime.seconds % 60}s
            |
            |COUNTERS:
            |  WS messages received: ${wsMessagesReceived.get()} (${wsMessagesReceived.get() / runningSeconds}/sec)
            |  HTTP calls made: ${httpCallsMade.get()} (${httpCallsMade.get() / runningSeconds}/sec)
            |  Head updates: ${headUpdates.get()} (${headUpdates.get() / runningSeconds}/sec)
            |  Errors: ${errors.get()}
            |
            |LATENCIES:
            |${formatLatencies()}
            |
            |EFFICIENCY:
            |  HTTP calls per head update: ${if (headUpdates.get() > 0) httpCallsMade.get().toDouble() / headUpdates.get() else 0.0}
            |  WS messages per head update: ${if (headUpdates.get() > 0) wsMessagesReceived.get().toDouble() / headUpdates.get() else 0.0}
            |============================================================
            """.trimMargin()
        )
    }

    private fun formatLatencies(): String {
        return latencies.entries.joinToString("\n") { (op, times) ->
            if (times.isEmpty()) {
                "  $op: no data"
            } else {
                val sorted = times.sorted()
                val avg = times.average()
                val p50 = sorted[sorted.size / 2]
                val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)]
                "  $op: avg=${avg.toLong()}ms, p50=${p50}ms, p95=${p95}ms, p99=${p99}ms, count=${times.size}"
            }
        }
    }

    fun toMap(): Map<String, Any> = mapOf(
        "strategy" to strategyName,
        "wsMessagesReceived" to wsMessagesReceived.get(),
        "httpCallsMade" to httpCallsMade.get(),
        "headUpdates" to headUpdates.get(),
        "errors" to errors.get(),
        "runningTimeMs" to Duration.between(startTime, Instant.now()).toMillis(),
    )
}

// ============================================================================
// STRATEGY INTERFACE
// ============================================================================

/**
 * Strategy interface for Solana head detection
 */
interface SolanaHeadStrategy {
    val name: String
    val metrics: SolanaHeadMetrics

    fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer>
    fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer>
    fun listenNewHeadsRequest(): ChainRequest
    fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest
}

// ============================================================================
// BLOCK SUBSCRIBE STRATEGY (Current Implementation)
// ============================================================================

/**
 * Original strategy using blockSubscribe WebSocket subscription.
 * - More expensive (full block data on each notification)
 * - Requires --rpc-pubsub-enable-block-subscription flag
 * - Returns complete block info including hash, timestamp
 */
class BlockSubscribeStrategy : SolanaHeadStrategy {
    override val name = "blockSubscribe"
    override val metrics = SolanaHeadMetrics(name)

    private val log = LoggerFactory.getLogger(BlockSubscribeStrategy::class.java)

    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        val startTime = System.currentTimeMillis()
        metrics.recordHttpCall() // getSlot

        return api.read(ChainRequest("getSlot", ListParams())).flatMap { slotResponse ->
            val slot = slotResponse.getResultAsProcessedString().toLong()
            metrics.recordHttpCall() // getBlocks

            api.read(
                ChainRequest(
                    "getBlocks",
                    ListParams(slot - 10, slot),
                ),
            ).flatMap { blocksResponse ->
                val response = Global.objectMapper.readValue(blocksResponse.getResult(), LongArray::class.java)
                if (response == null || response.isEmpty()) {
                    Mono.empty()
                } else {
                    metrics.recordHttpCall() // getBlock

                    api.read(
                        ChainRequest(
                            "getBlock",
                            ListParams(
                                response.max(),
                                mapOf(
                                    "commitment" to "confirmed",
                                    "showRewards" to false,
                                    "transactionDetails" to "none",
                                    "maxSupportedTransactionVersion" to 0,
                                ),
                            ),
                        ),
                    ).map { blockResponse ->
                        val raw = blockResponse.getResult()
                        val block = Global.objectMapper.readValue(raw, SolanaBlock::class.java)
                        metrics.recordLatency("getLatestBlock", System.currentTimeMillis() - startTime)
                        metrics.recordHeadUpdate()
                        makeBlock(raw, block, upstreamId, response.max())
                    }.onErrorResume { error ->
                        log.debug("error during getting last solana block - ${error.message}")
                        metrics.recordError()
                        Mono.empty()
                    }
                }
            }
        }
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val startTime = System.currentTimeMillis()
        metrics.recordWsMessage()

        return try {
            val res = Global.objectMapper.readValue(data, SolanaWrapper::class.java)
            metrics.recordLatency("getFromHeader", System.currentTimeMillis() - startTime)
            metrics.recordHeadUpdate()
            Mono.just(makeBlock(data, res.value.block, upstreamId, res.context.slot))
        } catch (e: Exception) {
            log.error("Failed to parse blockSubscribe notification", e)
            metrics.recordError()
            Mono.empty()
        }
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        return ChainRequest(
            "blockSubscribe",
            ListParams(
                "all",
                mapOf(
                    "commitment" to "confirmed",
                    "showRewards" to false,
                    "transactionDetails" to "none",
                ),
            ),
        )
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        return ChainRequest("blockUnsubscribe", ListParams(subId))
    }

    private fun makeBlock(raw: ByteArray, block: SolanaBlock, upstreamId: String, slot: Long): BlockContainer {
        return BlockContainer(
            height = block.height,
            hash = BlockId.fromBase64(block.hash),
            difficulty = BigInteger.ZERO,
            timestamp = Instant.ofEpochMilli(block.timestamp),
            full = false,
            json = raw,
            parsed = block,
            transactions = emptyList(),
            upstreamId = upstreamId,
            parentHash = BlockId.fromBase64(block.parent),
            slot = slot,
        )
    }
}

// ============================================================================
// SLOT SUBSCRIBE STRATEGY (New Optimized Implementation)
// ============================================================================

/**
 * Optimized strategy using slotSubscribe WebSocket subscription.
 * - Much cheaper (only slot/parent/root numbers)
 * - Stable API (no special flags needed)
 * - Throttled getBlockHeight calls (every N slots)
 * - Uses synthetic hash based on slot for ForkChoice deduplication
 */
class SlotSubscribeStrategy(
    private val heightCheckInterval: Int = 5,
) : SolanaHeadStrategy {
    override val name = "slotSubscribe"
    override val metrics = SolanaHeadMetrics(name)

    private val log = LoggerFactory.getLogger(SlotSubscribeStrategy::class.java)

    // Cache per upstream
    private val lastKnownHeights = ConcurrentHashMap<String, Long>()
    private val lastCheckedSlots = ConcurrentHashMap<String, Long>()

    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        val startTime = System.currentTimeMillis()
        metrics.recordHttpCall() // getSlot

        return api.read(ChainRequest("getSlot", ListParams()))
            .flatMap { slotResponse ->
                val slot = slotResponse.getResultAsProcessedString().toLong()
                metrics.recordHttpCall() // getBlockHeight

                api.read(ChainRequest("getBlockHeight", ListParams()))
                    .map { heightResponse ->
                        val blockHeight = heightResponse.getResultAsProcessedString().toLong()

                        // Update cache
                        lastKnownHeights[upstreamId] = blockHeight
                        lastCheckedSlots[upstreamId] = slot

                        metrics.recordLatency("getLatestBlock", System.currentTimeMillis() - startTime)
                        metrics.recordHeadUpdate()

                        makeBlockFromSlot(slot, blockHeight, upstreamId, ByteArray(0))
                    }
            }
            .onErrorResume { error ->
                log.debug("error during getting latest solana block - ${error.message}")
                metrics.recordError()
                Mono.empty()
            }
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val startTime = System.currentTimeMillis()
        metrics.recordWsMessage()

        return try {
            val notification = Global.objectMapper.readValue(data, SolanaSlotNotification::class.java)
            val slot = notification.slot

            val lastChecked = lastCheckedSlots[upstreamId] ?: 0L
            val shouldCheckHeight = slot - lastChecked >= heightCheckInterval

            if (shouldCheckHeight) {
                // Every N slots, make HTTP call for actual height
                metrics.recordHttpCall()

                api.read(ChainRequest("getBlockHeight", ListParams()))
                    .map { response ->
                        val blockHeight = response.getResultAsProcessedString().toLong()

                        // Update cache
                        lastKnownHeights[upstreamId] = blockHeight
                        lastCheckedSlots[upstreamId] = slot

                        metrics.recordLatency("getFromHeader_withHttp", System.currentTimeMillis() - startTime)
                        metrics.recordHeadUpdate()

                        makeBlockFromSlot(slot, blockHeight, upstreamId, data)
                    }
                    .onErrorResume { error ->
                        log.warn("Failed to get block height, using cached value: ${error.message}")
                        metrics.recordError()

                        // Fallback to cached height
                        val height = lastKnownHeights[upstreamId] ?: slot
                        metrics.recordHeadUpdate()
                        Mono.just(makeBlockFromSlot(slot, height, upstreamId, data))
                    }
            } else {
                // Between checks, use cached height
                val height = lastKnownHeights[upstreamId] ?: slot

                metrics.recordLatency("getFromHeader_cached", System.currentTimeMillis() - startTime)
                metrics.recordHeadUpdate()

                Mono.just(makeBlockFromSlot(slot, height, upstreamId, data))
            }
        } catch (e: Exception) {
            log.error("Failed to parse slotSubscribe notification", e)
            metrics.recordError()
            Mono.empty()
        }
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        return ChainRequest("slotSubscribe", ListParams())
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        return ChainRequest("slotUnsubscribe", ListParams(subId))
    }

    private fun makeBlockFromSlot(slot: Long, height: Long, upstreamId: String, data: ByteArray): BlockContainer {
        // Synthetic hash from slot for ForkChoice deduplication
        val syntheticHash = BlockId.from(
            ByteBuffer.allocate(32).putLong(slot).array()
        )

        return BlockContainer(
            height = height,
            hash = syntheticHash,
            difficulty = BigInteger.ZERO,
            timestamp = Instant.now(),
            full = false,
            json = data,
            parsed = null,
            transactions = emptyList(),
            upstreamId = upstreamId,
            parentHash = null,
            slot = slot,
        )
    }

    fun clearCache() {
        lastKnownHeights.clear()
        lastCheckedSlots.clear()
    }
}

// ============================================================================
// STRATEGY SELECTOR
// ============================================================================

enum class SolanaHeadStrategyType {
    BLOCK_SUBSCRIBE,  // Original (blockSubscribe)
    SLOT_SUBSCRIBE,   // Optimized (slotSubscribe)
}

// ============================================================================
// MAIN WRAPPER CLASS
// ============================================================================

object SolanaChainSpecific : AbstractChainSpecific() {

    private val log = LoggerFactory.getLogger(SolanaChainSpecific::class.java)

    // Strategy configuration - read from environment variable or default to BLOCK_SUBSCRIBE
    @Volatile
    var strategyType: SolanaHeadStrategyType = initStrategyFromEnv()

    // Strategy instances
    private val blockSubscribeStrategy = BlockSubscribeStrategy()
    private val slotSubscribeStrategy = SlotSubscribeStrategy(heightCheckInterval = 5)

    // Metrics logging scheduler
    private val metricsScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "solana-metrics-logger").apply { isDaemon = true }
    }

    // Metrics output file (set via env var or default)
    private val metricsOutputFile: String = System.getenv("SOLANA_METRICS_FILE")
        ?: "solana-metrics-${strategyType.name.lowercase()}.log"

    init {
        log.info("=".repeat(60))
        log.info("SOLANA HEAD STRATEGY: ${strategyType.name}")
        log.info("Metrics will be logged every 60 seconds")
        log.info("Metrics output file: $metricsOutputFile")
        log.info("=".repeat(60))

        // Start periodic metrics logging
        metricsScheduler.scheduleAtFixedRate(
            { logMetricsToFileAndConsole() },
            60, // initial delay
            60, // period
            TimeUnit.SECONDS
        )

        // Add shutdown hook to save final metrics
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Shutdown detected, saving final metrics...")
            saveFinalMetrics()
        })
    }

    private fun initStrategyFromEnv(): SolanaHeadStrategyType {
        val envValue = System.getenv("SOLANA_HEAD_STRATEGY")
        return when (envValue?.uppercase()) {
            "SLOT_SUBSCRIBE", "SLOT" -> {
                LoggerFactory.getLogger(SolanaChainSpecific::class.java)
                    .info("Using SLOT_SUBSCRIBE strategy from environment variable")
                SolanaHeadStrategyType.SLOT_SUBSCRIBE
            }
            "BLOCK_SUBSCRIBE", "BLOCK", null -> {
                LoggerFactory.getLogger(SolanaChainSpecific::class.java)
                    .info("Using BLOCK_SUBSCRIBE strategy (default or from environment variable)")
                SolanaHeadStrategyType.BLOCK_SUBSCRIBE
            }
            else -> {
                LoggerFactory.getLogger(SolanaChainSpecific::class.java)
                    .warn("Unknown SOLANA_HEAD_STRATEGY='$envValue', defaulting to BLOCK_SUBSCRIBE")
                SolanaHeadStrategyType.BLOCK_SUBSCRIBE
            }
        }
    }

    // Current active strategy
    val currentStrategy: SolanaHeadStrategy
        get() = when (strategyType) {
            SolanaHeadStrategyType.BLOCK_SUBSCRIBE -> blockSubscribeStrategy
            SolanaHeadStrategyType.SLOT_SUBSCRIBE -> slotSubscribeStrategy
        }

    private fun logMetricsToFileAndConsole() {
        try {
            val metrics = currentStrategy.metrics
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val runningTime = Duration.between(metrics.startTime, Instant.now())

            val metricsLine = buildString {
                appendLine("[$timestamp] Strategy: ${metrics.strategyName}")
                appendLine("  Running: ${runningTime.toMinutes()}m ${runningTime.seconds % 60}s")
                appendLine("  WS messages: ${metrics.wsMessagesReceived.get()}")
                appendLine("  HTTP calls: ${metrics.httpCallsMade.get()}")
                appendLine("  Head updates: ${metrics.headUpdates.get()}")
                appendLine("  Errors: ${metrics.errors.get()}")
                val headUpdates = metrics.headUpdates.get()
                if (headUpdates > 0) {
                    appendLine("  HTTP/head: %.3f".format(metrics.httpCallsMade.get().toDouble() / headUpdates))
                }
                appendLine("-".repeat(50))
            }

            // Log to console
            log.info("\n$metricsLine")

            // Append to file
            File(metricsOutputFile).appendText(metricsLine)
        } catch (e: Exception) {
            log.error("Failed to log metrics", e)
        }
    }

    private fun saveFinalMetrics() {
        try {
            val metrics = currentStrategy.metrics
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val runningTime = Duration.between(metrics.startTime, Instant.now())
            val runningSeconds = runningTime.seconds.coerceAtLeast(1)
            val headUpdates = metrics.headUpdates.get()

            val finalReport = buildString {
                appendLine()
                appendLine("=".repeat(60))
                appendLine("FINAL METRICS REPORT: ${metrics.strategyName}")
                appendLine("=".repeat(60))
                appendLine("Timestamp: $timestamp")
                appendLine("Total running time: ${runningTime.toMinutes()}m ${runningTime.seconds % 60}s")
                appendLine()
                appendLine("TOTALS:")
                appendLine("  WS messages received: ${metrics.wsMessagesReceived.get()}")
                appendLine("  HTTP calls made: ${metrics.httpCallsMade.get()}")
                appendLine("  Head updates: ${headUpdates}")
                appendLine("  Errors: ${metrics.errors.get()}")
                appendLine()
                appendLine("RATES (per second):")
                appendLine("  WS messages/sec: %.2f".format(metrics.wsMessagesReceived.get().toDouble() / runningSeconds))
                appendLine("  HTTP calls/sec: %.2f".format(metrics.httpCallsMade.get().toDouble() / runningSeconds))
                appendLine("  Head updates/sec: %.2f".format(headUpdates.toDouble() / runningSeconds))
                appendLine()
                appendLine("EFFICIENCY:")
                if (headUpdates > 0) {
                    appendLine("  HTTP calls per head update: %.3f".format(metrics.httpCallsMade.get().toDouble() / headUpdates))
                    appendLine("  WS messages per head update: %.3f".format(metrics.wsMessagesReceived.get().toDouble() / headUpdates))
                }
                appendLine("=".repeat(60))
            }

            log.info(finalReport)
            File(metricsOutputFile).appendText(finalReport)

            // Also save to JSON for easier parsing
            val jsonFile = metricsOutputFile.replace(".log", ".json")
            val json = """
            {
                "strategy": "${metrics.strategyName}",
                "timestamp": "$timestamp",
                "runningTimeSeconds": ${runningTime.seconds},
                "wsMessagesReceived": ${metrics.wsMessagesReceived.get()},
                "httpCallsMade": ${metrics.httpCallsMade.get()},
                "headUpdates": ${headUpdates},
                "errors": ${metrics.errors.get()},
                "wsPerSecond": ${metrics.wsMessagesReceived.get().toDouble() / runningSeconds},
                "httpPerSecond": ${metrics.httpCallsMade.get().toDouble() / runningSeconds},
                "headUpdatesPerSecond": ${headUpdates.toDouble() / runningSeconds},
                "httpPerHeadUpdate": ${if (headUpdates > 0) metrics.httpCallsMade.get().toDouble() / headUpdates else 0.0}
            }
            """.trimIndent()
            File(jsonFile).writeText(json)
            log.info("Metrics saved to $metricsOutputFile and $jsonFile")
        } catch (e: Exception) {
            log.error("Failed to save final metrics", e)
        }
    }

    /**
     * Switch strategy at runtime
     */
    fun switchStrategy(newStrategy: SolanaHeadStrategyType) {
        if (strategyType != newStrategy) {
            log.info("Switching Solana head strategy from ${strategyType.name} to ${newStrategy.name}")
            currentStrategy.metrics.logSummary()
            strategyType = newStrategy
            currentStrategy.metrics.reset()
            log.info("Now using strategy: ${currentStrategy.name}")
        }
    }

    /**
     * Log metrics summary for current strategy
     */
    fun logMetrics() {
        currentStrategy.metrics.logSummary()
    }

    /**
     * Get metrics for both strategies (for comparison)
     */
    fun getAllMetrics(): Map<String, Map<String, Any>> = mapOf(
        "blockSubscribe" to blockSubscribeStrategy.metrics.toMap(),
        "slotSubscribe" to slotSubscribeStrategy.metrics.toMap(),
    )

    /**
     * Reset all metrics
     */
    fun resetAllMetrics() {
        blockSubscribeStrategy.metrics.reset()
        slotSubscribeStrategy.metrics.reset()
    }

    // ========================================================================
    // Delegated methods to current strategy
    // ========================================================================

    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        log.trace("getLatestBlock using strategy: ${currentStrategy.name}")
        return currentStrategy.getLatestBlock(api, upstreamId)
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        log.trace("getFromHeader using strategy: ${currentStrategy.name}")
        return currentStrategy.getFromHeader(data, upstreamId, api)
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        log.debug("listenNewHeadsRequest using strategy: ${currentStrategy.name}")
        return currentStrategy.listenNewHeadsRequest()
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        return currentStrategy.unsubscribeNewHeadsRequest(subId)
    }

    // ========================================================================
    // Non-strategy methods (unchanged)
    // ========================================================================

    override fun upstreamValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<UpstreamAvailability>> {
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("getHealth", ListParams()),
                upstream,
            ) { data ->
                val resp = String(data)
                if (resp == "\"ok\"") {
                    UpstreamAvailability.OK
                } else {
                    log.warn("Upstream {} validation failed, solana status is {}", upstream.getId(), resp)
                    UpstreamAvailability.UNAVAILABLE
                }
            },
        )
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        return listOf()
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return SolanaLowerBoundService(chain, upstream)
    }

    override fun upstreamSettingsDetector(chain: Chain, upstream: Upstream): UpstreamSettingsDetector {
        return SolanaUpstreamSettingsDetector(upstream)
    }

    override fun makeIngressSubscription(chain: Chain, ws: WsSubscriptions): IngressSubscription {
        return GenericIngressSubscription(chain, ws, DefaultSolanaMethods.subs.map { it.first })
    }

    override fun subscriptionBuilder(headScheduler: Scheduler): (Multistream) -> EgressSubscription {
        return { ms -> GenericEgressSubscription(ms, headScheduler) }
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

// blockSubscribe response format
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaWrapper(
    @param:JsonProperty("context") var context: SolanaContext,
    @param:JsonProperty("value") var value: SolanaResult,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaContext(
    @param:JsonProperty("slot") var slot: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaResult(
    @param:JsonProperty("block") var block: SolanaBlock,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaBlock(
    @param:JsonProperty("blockHeight") var height: Long,
    @param:JsonProperty("blockTime") var timestamp: Long,
    @param:JsonProperty("blockhash") var hash: String,
    @param:JsonProperty("previousBlockhash") var parent: String,
)

// slotSubscribe response format
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaSlotNotification(
    @param:JsonProperty("slot") val slot: Long,
    @param:JsonProperty("parent") val parent: Long,
    @param:JsonProperty("root") val root: Long,
)
