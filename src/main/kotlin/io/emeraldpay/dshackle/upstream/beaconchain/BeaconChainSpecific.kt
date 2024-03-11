package io.emeraldpay.dshackle.upstream.beaconchain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.LabelsDetector
import io.emeraldpay.dshackle.upstream.LowerBoundBlockDetector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamValidator
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.TimeUnit

object BeaconChainSpecific : AbstractPollChainSpecific() {
    override fun latestBlockRequest(): ChainRequest {
        return ChainRequest(
            "GET#/eth/v2/beacon/blocks/head",
            RestParams.emptyParams(),
        )
    }

    override fun parseBlock(data: ByteArray, upstreamId: String): BlockContainer {
        val block = Global.objectMapper.readValue(data, BeaconChainBlock::class.java).data.message

        return BlockContainer(
            height = block.slot.toLong(),
            hash = BlockId.fromBase64(block.body.executionPayload.hash),
            difficulty = BigInteger.ZERO,
            timestamp = Instant.ofEpochMilli(TimeUnit.MILLISECONDS.convert(block.body.executionPayload.timestamp.toLong(), TimeUnit.NANOSECONDS)),
            full = false,
            json = data,
            parsed = block,
            transactions = emptyList(),
            upstreamId = upstreamId,
            parentHash = BlockId.fromBase64(block.body.executionPayload.parentHash),
        )
    }

    override fun parseHeader(data: ByteArray, upstreamId: String): BlockContainer {
        throw NotImplementedError()
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw NotImplementedError()
    }

    override fun unsubscribeNewHeadsRequest(subId: String): ChainRequest {
        throw NotImplementedError()
    }

    override fun labelDetector(chain: Chain, reader: ChainReader): LabelsDetector {
        return BeaconChainLabelsDetector(reader)
    }

    override fun validator(
        chain: Chain,
        upstream: Upstream,
        options: ChainOptions.Options,
        config: ChainsConfig.ChainConfig,
    ): UpstreamValidator {
        return BeaconChainValidator(upstream, options)
    }

    override fun lowerBoundBlockDetector(chain: Chain, upstream: Upstream): LowerBoundBlockDetector {
        return BeaconChainLowerBoundBlockDetector(chain, upstream)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainBlock(
    @JsonProperty("data")
    var data: BeaconChainData,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainData(
    @JsonProperty("message")
    var message: BeaconChainMessage,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainMessage(
    @JsonProperty("slot")
    var slot: String,
    @JsonProperty("body")
    var body: BeaconChainMessageBody,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainMessageBody(
    @JsonProperty("execution_payload")
    var executionPayload: BeaconChainExecutionPayload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainExecutionPayload(
    @JsonProperty("parent_hash")
    var parentHash: String,
    @JsonProperty("timestamp")
    var timestamp: String,
    @JsonProperty("block_hash")
    var hash: String,
)
