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
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.LabelsDetector
import io.emeraldpay.dshackle.upstream.LowerBoundBlockDetector
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamValidator
import io.emeraldpay.dshackle.upstream.generic.AbstractChainSpecific
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.TimeUnit

object BeaconChainSpecific : AbstractChainSpecific() {
    override fun parseHeader(data: ByteArray, upstreamId: String): BlockContainer {
        throw NotImplementedError()
    }

    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        return api.read(
            ChainRequest("GET#/eth/v2/beacon/blocks/head", RestParams.emptyParams()),
        )
            .flatMap(ChainResponse::requireResult)
            .flatMap { beaconBlock ->
                val parsedBeaconBlock = Global.objectMapper.readValue(beaconBlock, BeaconChainBlock::class.java).data.message

                api.read(
                    ChainRequest("GET#/eth/v1/beacon/blocks/${parsedBeaconBlock.slot}/root", RestParams.emptyParams()),
                )
                    .flatMap(ChainResponse::requireResult)
                    .map { root ->
                        val blockRoot = Global.objectMapper.readValue(root, BeaconChainBlockRoot::class.java).data.root

                        BlockContainer(
                            height = parsedBeaconBlock.slot.toLong(),
                            hash = BlockId.from(blockRoot),
                            difficulty = BigInteger.ZERO,
                            timestamp = Instant.ofEpochMilli(
                                TimeUnit.MILLISECONDS.convert(
                                    parsedBeaconBlock.body.executionPayload.timestamp.toLong(),
                                    TimeUnit.SECONDS,
                                ),
                            ),
                            full = false,
                            json = beaconBlock,
                            parsed = parsedBeaconBlock,
                            transactions = emptyList(),
                            upstreamId = upstreamId,
                            parentHash = BlockId.from(parsedBeaconBlock.parentRoot),
                        )
                    }
            }
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
    @JsonProperty("parent_root")
    var parentRoot: String,
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
    @JsonProperty("timestamp")
    var timestamp: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BeaconChainBlockRoot(
    @JsonProperty("data")
    var data: BeaconChainBlockRootData,
)

data class BeaconChainBlockRootData(
    @JsonProperty("root")
    var root: String,
)
