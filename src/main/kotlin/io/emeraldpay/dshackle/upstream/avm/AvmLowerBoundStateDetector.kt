package io.emeraldpay.dshackle.upstream.avm

import com.fasterxml.jackson.databind.JsonNode
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

class AvmLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.STATE, notFoundErrors, lowerBounds)

    companion object {
        val notFoundErrors = setOf(
            "block not found",
            "not available",
            "does not have entry",
            "failed to retrieve information",
            "no information found",
        )
    }

    override fun period(): Long = 60

    override fun internalDetectLowerBound(): Flux<LowerBoundData> {
        return recursiveLowerBound.recursiveDetectLowerBound { block ->
            val round = if (block <= 0L) 1L else block
            val params = RestParams(
                headers = emptyList(),
                queryParams = emptyList(),
                pathParams = listOf(round.toString()),
                payload = ByteArray(0),
            )
            upstream.getIngressReader()
                .read(ChainRequest("GET#/v2/blocks/*/hash", params))
                .timeout(Defaults.internalCallsTimeout)
                .map { response -> interpretHashResponse(round, response) }
        }.toFlux()
    }

    override fun types(): Set<LowerBoundType> = setOf(LowerBoundType.STATE)

    private fun interpretHashResponse(round: Long, response: ChainResponse): ChainResponse {
        if (response.hasError()) {
            return response
        }
        val raw = response.getResult()
        if (raw.isEmpty()) {
            return ChainResponse(null, ChainCallError(404, "empty body for round $round"))
        }
        val node = runCatching { Global.objectMapper.readTree(raw) }.getOrNull() ?: return response
        val message = node.get("message")?.asText().orEmpty()
        if (message.isNotBlank() && looksLikeNotFound(message)) {
            return ChainResponse(null, ChainCallError(404, message))
        }
        if (!hasHashPayload(node)) {
            return ChainResponse(null, ChainCallError(404, "round $round not available"))
        }
        return response
    }

    private fun looksLikeNotFound(message: String): Boolean {
        val lower = message.lowercase()
        return notFoundErrors.any { lower.contains(it) }
    }

    private fun hasHashPayload(node: JsonNode): Boolean {
        val hash = node.get("blockHash")?.asText().orEmpty()
        return hash.isNotBlank()
    }
}
