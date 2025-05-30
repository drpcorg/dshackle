package io.emeraldpay.dshackle.upstream.starknet

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
import io.emeraldpay.dshackle.upstream.GenericSingleCallValidator
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object StarknetChainSpecific : AbstractPollChainSpecific() {

    private val log = LoggerFactory.getLogger(StarknetChainSpecific::class.java)

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val block = Global.objectMapper.readValue(data, StarknetBlock::class.java)

        return Mono.just(
            BlockContainer(
                height = block.number,
                hash = BlockId.from(block.hash),
                difficulty = BigInteger.ZERO,
                timestamp = block.timestamp,
                full = false,
                json = data,
                parsed = block,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = BlockId.from(block.parent),
            ),
        )
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw NotImplementedError()
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw NotImplementedError()
    }

    override fun unsubscribeNewHeadsRequest(subId: String): ChainRequest {
        throw NotImplementedError()
    }

    override fun upstreamValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<UpstreamAvailability>> {
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("starknet_syncing", ListParams()),
                upstream,
            ) { data ->
                validate(data, config.laggingLagSize, upstream.getId())
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
        return StarknetLowerBoundService(chain, upstream)
    }

    fun validate(data: ByteArray, lagging: Int, upstreamId: String): UpstreamAvailability {
        val raw = Global.objectMapper.readTree(data)
        if (raw.isBoolean) {
            return if (raw.asBoolean()) {
                UpstreamAvailability.SYNCING
            } else {
                UpstreamAvailability.OK
            }
        }
        val resp = Global.objectMapper.treeToValue(raw, StarknetSyncing::class.java)
        return if (resp.highest - resp.current > lagging) {
            log.warn("Starknet node {} is syncing: current={} and highest={}", upstreamId, resp.current, resp.highest)
            UpstreamAvailability.SYNCING
        } else {
            UpstreamAvailability.OK
        }
    }

    override fun latestBlockRequest(): ChainRequest =
        ChainRequest("starknet_getBlockWithTxHashes", ListParams("latest"))

    override fun upstreamSettingsDetector(
        chain: Chain,
        upstream: Upstream,
    ): UpstreamSettingsDetector {
        return StarknetUpstreamSettingsDetector(upstream, chain)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StarknetBlock(
    @JsonProperty("block_hash") var hash: String,
    @JsonProperty("block_number") var number: Long,
    @JsonProperty("timestamp") var timestamp: Instant,
    @JsonProperty("parent_hash") var parent: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StarknetSyncing(
    @JsonProperty("current_block_num") var current: Long,
    @JsonProperty("highest_block_num") var highest: Long,
)
