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
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object AvmChainSpecific : AbstractPollChainSpecific() {

    private val log = LoggerFactory.getLogger(AvmChainSpecific::class.java)

    override fun latestBlockRequest(): ChainRequest =
        ChainRequest("algod_getBlock", ListParams("latest"))

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val response = Global.objectMapper.readValue(data, AvmBlockResult::class.java)
        val block = response.block

        return Mono.just(
            BlockContainer(
                height = block.round,
                hash = BlockId.from(toHashBytes(block.seed ?: block.txnRoot, block.round)),
                difficulty = BigInteger.ZERO,
                timestamp = Instant.ofEpochSecond(block.timestamp),
                full = false,
                json = data,
                parsed = response,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = BlockId.from(toHashBytes(block.previousBlockHash, block.round - 1)),
            ),
        )
    }

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
                stripped.toByteArray(Charsets.UTF_8).let {
                    if (it.size < 32) it + ByteArray(32 - it.size) else it.copyOfRange(0, 32)
                }
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
                ChainRequest("algod_status", ListParams()),
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
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("algod_genesis", ListParams()),
                upstream,
            ) { data ->
                val genesis = Global.objectMapper.readValue(data, AvmGenesis::class.java)
                val expected = chain.netVersion.toString()
                if (expected != BigInteger.ZERO.toString() && genesis.network.isNotEmpty() &&
                    genesis.network.lowercase() != expected.lowercase() &&
                    chain.chainId.isNotEmpty() && genesis.network.lowercase() != chain.chainId.lowercase()
                ) {
                    log.warn(
                        "AVM upstream {} reports network '{}' which doesn't match expected '{}'",
                        upstream.getId(),
                        genesis.network,
                        chain.chainId,
                    )
                    ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
                } else {
                    ValidateUpstreamSettingsResult.UPSTREAM_VALID
                }
            },
        )
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
}

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
data class AvmStatus(
    @param:JsonProperty("last-round") var lastRound: Long = 0,
    @param:JsonProperty("catchup-time") var catchupTime: Long = 0,
    @param:JsonProperty("time-since-last-round") var timeSinceLastRound: Long = 0,
    @param:JsonProperty("last-version") var lastVersion: String? = null,
    @param:JsonProperty("next-version") var nextVersion: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AvmGenesis(
    @param:JsonProperty("network") var network: String = "",
    @param:JsonProperty("id") var id: String = "",
    @param:JsonProperty("proto") var proto: String = "",
)
