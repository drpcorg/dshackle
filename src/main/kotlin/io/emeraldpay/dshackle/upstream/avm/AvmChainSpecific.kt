package io.emeraldpay.dshackle.upstream.avm

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
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object AvmChainSpecific : AbstractPollChainSpecific() {

    private val log = LoggerFactory.getLogger(AvmChainSpecific::class.java)

    override fun latestBlockRequest(): ChainRequest =
        ChainRequest("GET#/v2/status", RestParams.emptyParams())

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val status = Global.objectMapper.readValue(data, AvmStatus::class.java)
        val round = status.lastRound
        val blockRequest = ChainRequest(
            "GET#/v2/blocks/$round",
            RestParams(
                headers = emptyList(),
                queryParams = listOf("format" to "json", "header-only" to "true"),
                pathParams = emptyList(),
                payload = ByteArray(0),
            ),
        )
        return api.read(blockRequest)
            .map { resp ->
                val blockData = resp.getResult()
                val block = Global.objectMapper.readValue(blockData, AvmBlockResult::class.java).block
                BlockContainer(
                    height = block.round,
                    hash = BlockId.from(toHashBytes(block.seed ?: block.txnRoot, block.round)),
                    difficulty = BigInteger.ZERO,
                    timestamp = Instant.ofEpochSecond(block.timestamp),
                    full = false,
                    json = blockData,
                    parsed = block,
                    transactions = emptyList(),
                    upstreamId = upstreamId,
                    parentHash = BlockId.from(toHashBytes(block.previousBlockHash, block.round - 1)),
                )
            }
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw NotImplementedError()
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw NotImplementedError()
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
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
                ChainRequest("GET#/v2/status", RestParams.emptyParams()),
                upstream,
            ) { data ->
                validate(data, upstream.getId())
            },
        )
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        return emptyList()
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return AvmLowerBoundService(chain, upstream)
    }

    fun validate(data: ByteArray, upstreamId: String): UpstreamAvailability {
        val status = Global.objectMapper.readValue(data, AvmStatus::class.java)
        return if (status.catchupTime > 0L) {
            log.warn("AVM node {} is catching up: catchupTime={}ns", upstreamId, status.catchupTime)
            UpstreamAvailability.SYNCING
        } else {
            UpstreamAvailability.OK
        }
    }

    // Algorand JSON blocks encode 32-byte fields (seed, prev, txn) in base64.
    // Decode to raw bytes; if decoding fails or the field is absent, fall back
    // to a deterministic 32-byte encoding of the round number.
    private fun toHashBytes(raw: String?, round: Long): ByteArray {
        if (raw.isNullOrBlank()) {
            return roundToBytes(round)
        }
        val stripped = raw.removePrefix("blk-")
        return try {
            java.util.Base64.getDecoder().decode(stripped)
        } catch (_: IllegalArgumentException) {
            try {
                java.util.Base64.getUrlDecoder().decode(stripped)
            } catch (_: IllegalArgumentException) {
                roundToBytes(round)
            }
        }
    }

    private fun roundToBytes(round: Long): ByteArray {
        val bytes = ByteArray(32)
        var value = if (round < 0) 0L else round
        for (i in 0 until 8) {
            bytes[31 - i] = (value and 0xff).toByte()
            value = value ushr 8
        }
        return bytes
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvmStatus(
    @param:JsonProperty("last-round") var lastRound: Long = 0,
    @param:JsonProperty("catchup-time") var catchupTime: Long = 0,
    @param:JsonProperty("time-since-last-round") var timeSinceLastRound: Long = 0,
    @param:JsonProperty("last-version") var lastVersion: String? = null,
    @param:JsonProperty("next-version") var nextVersion: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvmBlockResult(
    @param:JsonProperty("block") var block: AvmBlock,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvmBlock(
    @param:JsonProperty("rnd") var round: Long,
    @param:JsonProperty("ts") var timestamp: Long,
    @param:JsonProperty("prev") var previousBlockHash: String? = null,
    @param:JsonProperty("seed") var seed: String? = null,
    @param:JsonProperty("txn") var txnRoot: String? = null,
    @param:JsonProperty("gh") var genesisHash: String? = null,
    @param:JsonProperty("gen") var genesisId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvmGenesis(
    @param:JsonProperty("network") var network: String = "",
    @param:JsonProperty("id") var id: String = "",
    @param:JsonProperty("proto") var proto: String = "",
)
