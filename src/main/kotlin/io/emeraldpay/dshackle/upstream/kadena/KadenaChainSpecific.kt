package io.emeraldpay.dshackle.upstream.kadena

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
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
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object KadenaChainSpecific : AbstractPollChainSpecific() {
    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val block = Global.objectMapper.readValue<KadenaHeader>(data)

        return Mono.just(
            BlockContainer(
                height = block.height,
                hash = BlockId.fromBase64(block.weight),
                difficulty = BigInteger.ZERO,
                timestamp = Instant.EPOCH,
                full = false,
                json = data,
                parsed = block,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = BlockId.fromBase64(""), // todo:
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
        var validators = listOf(
            GenericSingleCallValidator(
                ChainRequest("GET#/cut", RestParams.emptyParams()),
                upstream,
            ) { _ -> UpstreamAvailability.OK },
        )
//        if (options.validateSyncing) {
//            validators += GenericSingleCallValidator(
//                ChainRequest("GET#/eth/v1/node/syncing", RestParams.emptyParams()),
//                upstream,
//            ) { data ->
//                val syncing = Global.objectMapper.readValue(data, BeaconChainSyncing::class.java).data.isSyncing
//                upstream.getHead().onSyncingNode(syncing)
//                if (syncing) {
//                    UpstreamAvailability.SYNCING
//                } else {
//                    UpstreamAvailability.OK
//                }
//            }
//        }
//        if (options.validatePeers && options.minPeers > 0) {
//            validators += GenericSingleCallValidator(
//                ChainRequest("GET#/eth/v1/node/peer_count", RestParams.emptyParams()),
//                upstream,
//            ) { data ->
//                val connected = Global.objectMapper.readValue(
//                    data,
//                    BeaconChainPeers::class.java,
//                ).data.connected.toInt()
//                if (connected < options.minPeers) UpstreamAvailability.IMMATURE else UpstreamAvailability.OK
//            }
//        }
        return validators
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
        return KadenaLowerBoundService(chain, upstream)
    }

    fun validate(data: ByteArray): UpstreamAvailability {
//        val resp = Global.objectMapper.readValue(data, NearStatus::class.java)
//        return if (resp.syncInfo.syncing) {
//            UpstreamAvailability.SYNCING
//        } else {
//            UpstreamAvailability.OK
//        }
        return UpstreamAvailability.OK
    }

    fun validateSettings(data: ByteArray, chain: Chain): ValidateUpstreamSettingsResult {
//        val resp = Global.objectMapper.readValue(data, NearStatus::class.java)
//        return if (chain.chainId.isNotEmpty() && resp.chainId.lowercase() != chain.chainId.lowercase()) {
//            ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
//        } else {
//            ValidateUpstreamSettingsResult.UPSTREAM_VALID
//        }
        return ValidateUpstreamSettingsResult.UPSTREAM_VALID
    }

    override fun upstreamSettingsDetector(chain: Chain, upstream: Upstream): UpstreamSettingsDetector {
        return KadenaUpstreamSettingsDetector(upstream)
    }

    override fun latestBlockRequest(): ChainRequest {
        return ChainRequest("GET#/cut", RestParams.emptyParams())
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class KadenaHeader(
    @JsonProperty("height") var height: Long,
    @JsonProperty("weight") var weight: String,
    @JsonProperty("instance") var instance: String,
    @JsonProperty("id") var id: String,
)
