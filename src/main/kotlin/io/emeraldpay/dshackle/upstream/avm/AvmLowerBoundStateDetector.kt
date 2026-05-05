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

/**
 * Detects the lowest round for which the algod upstream still has block data.
 *
 * Algorand's algod REST API does not expose a lower-bound field directly:
 *  - `/v2/status` carries `last-round` only, no minimum.
 *  - `/v2/ledger/sync` (`GetSyncRound`) is an admin pin used during catchpoint
 *    catchup; it returns 400 once unset, so it cannot be relied on as a
 *    general source of truth.
 *
 * The cheapest universally-reliable signal is a probe of `/v2/blocks/{round}`:
 * algod returns 200 if the round is retained and 404 with a JSON
 * `{"message":"..."}` body once it has been pruned. We feed that probe to the
 * shared [RecursiveLowerBound] so the boundary converges in O(log latest_round)
 * RPCs and is then refreshed cheaply via the cached-bound fast path. Header-only
 * mode keeps each probe response small.
 */
class AvmLowerBoundStateDetector(
    private val upstream: Upstream,
) : LowerBoundDetector(upstream.getChain()) {

    private val recursiveLowerBound = RecursiveLowerBound(upstream, LowerBoundType.STATE, notFoundErrors, lowerBounds)

    companion object {
        // algod 404 payload examples:
        //   {"message":"failed to retrieve information from the ledger : block ... not found"}
        //   {"message":"requested block is not available"}
        //   {"message":"ledger does not have entry"}
        // Anchoring on substrings keeps the matcher tolerant of minor wording
        // changes between algod releases.
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
                queryParams = listOf("header-only" to "true"),
                pathParams = listOf(round.toString()),
                payload = ByteArray(0),
            )
            upstream.getIngressReader()
                .read(ChainRequest("GET#/v2/blocks/*", params))
                .timeout(Defaults.internalCallsTimeout)
                .map { response -> interpretBlockResponse(round, response) }
        }.toFlux()
    }

    override fun types(): Set<LowerBoundType> = setOf(LowerBoundType.STATE)

    /**
     * algod returns 4xx as a normal HTTP body for REST callers; the dshackle
     * REST reader surfaces it as a successful [ChainResponse] with the
     * upstream's JSON payload. Inspect the payload here so we can convert a
     * "block not retained" reply into a [ChainCallError] that
     * [RecursiveLowerBound] interprets as "no data at this round".
     */
    private fun interpretBlockResponse(round: Long, response: ChainResponse): ChainResponse {
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
        if (!hasBlockPayload(node)) {
            return ChainResponse(null, ChainCallError(404, "round $round not available"))
        }
        return response
    }

    private fun looksLikeNotFound(message: String): Boolean {
        val lower = message.lowercase()
        return notFoundErrors.any { lower.contains(it) }
    }

    private fun hasBlockPayload(node: JsonNode): Boolean {
        // GET /v2/blocks/{round} returns an object with a `block` field on success
        // (or `cert`/`block` for some flags). Treat absence of either as a miss.
        return node.has("block") || node.has("cert")
    }
}
