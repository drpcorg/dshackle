package io.emeraldpay.dshackle.upstream.beaconchain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundData
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.detector.RecursiveLowerBound
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Flux
import reactor.kotlin.core.publisher.toFlux

class BeaconChainLowerBoundBlobDetector(
    private val chain: Chain,
    private val upstream: Upstream,
) : LowerBoundDetector(chain) {
    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.BLOB, stateErrors, lowerBounds)

    companion object {
        const val MAX_OFFSET = 20
        val notFoundError = "NOT_FOUND:" // e.g. {"message":"NOT_FOUND: beacon block at slot 1086646","code":404}
        val notFoundError2 = "lock not found"
        val notFoundError3 = "has not been found"
        val stateErrors = setOf(notFoundError, notFoundError2, notFoundError3)
    }

    override fun period(): Long {
        return 5
    }

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBoundWithOffset(MAX_OFFSET) { block ->
            val restParams = RestParams(emptyList(), emptyList(), listOf(block.toString()), ByteArray(0))

            upstream.getIngressReader()
                .read(ChainRequest("GET#/eth/v1/beacon/blob_sidecars/*", restParams))
                .flatMap(ChainResponse::requireResult)
                .timeout(Defaults.internalCallsTimeout)
                .map {
                    parseHeadersResponse(it)
                }
        }.toFlux()
    }

    override fun types(): Set<LowerBoundType> {
        return setOf(LowerBoundType.BLOB)
    }

    private fun parseHeadersResponse(data: ByteArray): ChainResponse {
        val node = Global.objectMapper.readValue<JsonNode>(data)
        if (node.get("code") != null && node.get("message") != null && node.get("code").textValue() == "404") {
            return ChainResponse(null, ChainCallError(node.get("code").asInt(), node.get("message").asText(), node.get("message").asText()))
        }
        if (node.get("data").toString() == "[]") {
            return ChainResponse(null, ChainCallError(404, notFoundError))
        }
        return ChainResponse(node.get("data").toString().toByteArray(), null)
    }
}
