package io.emeraldpay.dshackle.upstream.tronhttp

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.SingleCallValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.UpstreamValidator
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.generic.GenericUpstreamValidator
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.near.NearChainSpecific.validate
import io.emeraldpay.dshackle.upstream.near.NearChainSpecific.validateSettings
import io.emeraldpay.dshackle.upstream.near.NearStatus
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object TronHttpSpecific : AbstractPollChainSpecific() {
    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw NotImplementedError()
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw NotImplementedError()
    }

    override fun unsubscribeNewHeadsRequest(subId: String): ChainRequest {
        throw NotImplementedError()
    }

    override fun latestBlockRequest(): ChainRequest {
        return ChainRequest("POST#/wallet/getblock", RestParams(emptyList(), emptyList(), emptyList(), "{}".encodeToByteArray()))
    }

    override fun parseBlock(data: ByteArray, upstreamId: String): BlockContainer {
        val blockHeader = Global.objectMapper.readValue<TronHttpBlockHeader>(data)

        return BlockContainer(
            height = blockHeader.number,
            hash = BlockId.from(blockHeader.blockID),
            difficulty = BigInteger.ZERO,
            timestamp = Instant.EPOCH,
            full = false,
            json = data,
            parsed = blockHeader,
            transactions = emptyList(),
            upstreamId = upstreamId,
            parentHash = BlockId.from(blockHeader.parentHash),
        )
    }

    override fun upstreamSettingsDetector(chain: Chain, upstream: Upstream): UpstreamSettingsDetector {
        return TronHttpUpstreamSettingsDetector(upstream)
    }

    override fun validator(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): UpstreamValidator {
        return GenericUpstreamValidator(
            upstream,
            options,
            listOf(
                SingleCallValidator(
                    ChainRequest("GET#/wallet/getnodeinfo", RestParams.emptyParams()),
                ) { data ->
                    validate(data)
                },
            ),
            listOf(
                SingleCallValidator(
                    ChainRequest("GET#/wallet/getnodeinfo", RestParams.emptyParams()),
                ) { data ->
                    validateSettings(data, chain)
                },
            ),
        )
    }

    private fun validate(data: ByteArray): UpstreamAvailability {
        return UpstreamAvailability.OK
    }

    private fun validateSettings(data: ByteArray, chain: Chain): ValidateUpstreamSettingsResult {
        return ValidateUpstreamSettingsResult.UPSTREAM_VALID
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return TronHttpLowerBoundService(chain, upstream)
    }
}

data class TronHttpBlockHeader(
    val blockID: String,
    val parentHash: String,
    val number: Long,
)

class TronHttpBlockHeaderDeserializer : JsonDeserializer<TronHttpBlockHeader>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TronHttpBlockHeader {
        val node = p.readValueAsTree<JsonNode>()
        val blockHeader = node["block_header"]
        val rawData = blockHeader["raw_data"]
        val blockId = node["blockID"].textValue()
        val parentHash = rawData["parentHash"].textValue()
        val number = rawData["number"].numberValue().toLong()
        return TronHttpBlockHeader(blockId, parentHash, number)
    }
}
