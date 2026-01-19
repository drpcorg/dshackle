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
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Solana chain-specific implementation using slotSubscribe for head detection.
 *
 * Uses lightweight slotSubscribe WebSocket subscription instead of expensive blockSubscribe:
 * - ~50 bytes per notification vs ~1KB for blockSubscribe
 * - Stable API (no special node flags required)
 * - Universal provider support
 * - Throttled getEpochInfo calls (every N slots) to get actual slot and block height
 * - Synthetic hash based on slot for ForkChoice deduplication
 */
object SolanaChainSpecific : AbstractChainSpecific() {

    private val log = LoggerFactory.getLogger(SolanaChainSpecific::class.java)

    // Throttle: check actual block height every N slots
    private const val HEIGHT_CHECK_INTERVAL = 5

    // Cache per upstream for throttling
    private val lastKnownHeights = ConcurrentHashMap<String, Long>()
    private val lastCheckedSlots = ConcurrentHashMap<String, Long>()

    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        return api.read(ChainRequest("getEpochInfo", ListParams()))
            .map { response ->
                val epochInfo = Global.objectMapper.readValue(
                    response.getResult(),
                    SolanaEpochInfo::class.java,
                )
                lastKnownHeights[upstreamId] = epochInfo.blockHeight
                lastCheckedSlots[upstreamId] = epochInfo.absoluteSlot
                makeBlockFromSlot(epochInfo.absoluteSlot, epochInfo.absoluteSlot - 1, epochInfo.blockHeight, upstreamId, ByteArray(0))
            }
            .onErrorResume { error ->
                log.debug("error during getting latest solana block - ${error.message}")
                Mono.empty()
            }
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        return try {
            val notification = Global.objectMapper.readValue(data, SolanaSlotNotification::class.java)
            val slot = notification.slot

            val lastChecked = lastCheckedSlots[upstreamId] ?: 0L
            val lastHeight = lastKnownHeights[upstreamId]
            val shouldCheckHeight = slot - lastChecked >= HEIGHT_CHECK_INTERVAL

            // Optimistic height estimation: assume 1:1 slot-to-block ratio
            val estimatedHeight = if (lastHeight != null && lastChecked > 0) {
                lastHeight + (slot - lastChecked)
            } else {
                null
            }

            if (shouldCheckHeight || estimatedHeight == null) {
                // Verify actual height using getEpochInfo (single call for both slot and height)
                api.read(ChainRequest("getEpochInfo", ListParams()))
                    .map { response ->
                        val epochInfo = Global.objectMapper.readValue(
                            response.getResult(),
                            SolanaEpochInfo::class.java,
                        )
                        val actualSlot = epochInfo.absoluteSlot
                        val actualHeight = epochInfo.blockHeight

                        if (estimatedHeight != null && estimatedHeight != actualHeight) {
                            log.debug(
                                "Height drift detected for {}: estimated={}, actual={}, diff={}",
                                upstreamId,
                                estimatedHeight,
                                actualHeight,
                                estimatedHeight - actualHeight,
                            )
                        }

                        lastKnownHeights[upstreamId] = actualHeight
                        lastCheckedSlots[upstreamId] = actualSlot
                        makeBlockFromSlot(actualSlot, actualSlot - 1, actualHeight, upstreamId, data)
                    }
                    .onErrorResume { error ->
                        log.warn("Failed to get block height, using estimated value: ${error.message}")
                        val height = estimatedHeight ?: lastHeight ?: slot
                        Mono.just(makeBlockFromSlot(slot, notification.parent, height, upstreamId, data))
                    }
            } else {
                // Use optimistic estimated height
                Mono.just(makeBlockFromSlot(slot, notification.parent, estimatedHeight, upstreamId, data))
            }
        } catch (e: Exception) {
            log.error("Failed to parse slotSubscribe notification", e)
            Mono.empty()
        }
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        return ChainRequest("slotSubscribe", ListParams())
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        return ChainRequest("slotUnsubscribe", ListParams(subId))
    }

    private fun makeBlockFromSlot(slot: Long, parentSlot: Long, height: Long, upstreamId: String, data: ByteArray): BlockContainer {
        // Synthetic hash from slot for ForkChoice deduplication
        val syntheticHash = BlockId.from(
            ByteBuffer.allocate(32).putLong(slot).array(),
        )
        // Synthetic parent hash from parent slot for chain tracking
        val syntheticParentHash = BlockId.from(
            ByteBuffer.allocate(32).putLong(parentSlot).array(),
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
            parentHash = syntheticParentHash,
            slot = slot,
        )
    }

    // For testing only - clear height cache
    internal fun clearCache() {
        lastKnownHeights.clear()
        lastCheckedSlots.clear()
    }

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

// slotSubscribe response format
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaSlotNotification(
    @param:JsonProperty("slot") val slot: Long,
    @param:JsonProperty("parent") val parent: Long,
    @param:JsonProperty("root") val root: Long,
)

// getEpochInfo response format
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaEpochInfo(
    @param:JsonProperty("absoluteSlot") val absoluteSlot: Long,
    @param:JsonProperty("blockHeight") val blockHeight: Long,
    @param:JsonProperty("epoch") val epoch: Long,
    @param:JsonProperty("slotIndex") val slotIndex: Long,
    @param:JsonProperty("slotsInEpoch") val slotsInEpoch: Long,
)

// getBlock response format (used by SolanaLowerBoundSlotDetector)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SolanaBlock(
    @param:JsonProperty("blockHeight") val height: Long,
    @param:JsonProperty("blockhash") val hash: String,
    @param:JsonProperty("blockTime") val time: Long,
)
