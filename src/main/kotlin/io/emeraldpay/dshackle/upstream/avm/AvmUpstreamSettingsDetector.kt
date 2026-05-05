package io.emeraldpay.dshackle.upstream.avm

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.upstream.BasicUpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.NodeTypeRequest
import io.emeraldpay.dshackle.upstream.UNKNOWN_CLIENT_VERSION
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Flux

/**
 * Reads node identity from algod's `/v2/versions` endpoint, which returns:
 *
 * ```
 * {
 *   "build": {"major":3,"minor":24,"build_number":2,"branch":"rel/stable","channel":"stable", ...},
 *   "genesis_id": "mainnet-v1.0",
 *   "genesis_hash_b64": "...",
 *   "versions": ["v1","v2"]
 * }
 * ```
 *
 * algod is the only public algorand node implementation, so client_type is
 * fixed to `algod`. Client version is reconstructed from the build object
 * (major.minor.build_number) which is the form algorand themselves publish in
 * release notes.
 */
class AvmUpstreamSettingsDetector(
    upstream: Upstream,
) : BasicUpstreamSettingsDetector(upstream) {

    override fun internalDetectLabels(): Flux<Pair<String, String>> {
        return Flux.merge(
            detectNodeType(),
        )
    }

    override fun clientVersionRequest(): ChainRequest =
        ChainRequest("GET#/v2/versions", RestParams.emptyParams())

    override fun parseClientVersion(data: ByteArray): String {
        if (data.isEmpty()) return UNKNOWN_CLIENT_VERSION
        val node = runCatching { io.emeraldpay.dshackle.Global.objectMapper.readTree(data) }.getOrNull()
            ?: return UNKNOWN_CLIENT_VERSION
        return clientVersion(node) ?: UNKNOWN_CLIENT_VERSION
    }

    override fun nodeTypeRequest(): NodeTypeRequest = NodeTypeRequest(clientVersionRequest())

    override fun clientType(node: JsonNode): String = "algod"

    override fun clientVersion(node: JsonNode): String {
        val build = node.get("build") ?: return UNKNOWN_CLIENT_VERSION
        val major = build.get("major")?.asInt(-1) ?: -1
        val minor = build.get("minor")?.asInt(-1) ?: -1
        val patch = build.get("build_number")?.asInt(-1) ?: -1
        if (major < 0 || minor < 0 || patch < 0) {
            return UNKNOWN_CLIENT_VERSION
        }
        return "$major.$minor.$patch"
    }
}
