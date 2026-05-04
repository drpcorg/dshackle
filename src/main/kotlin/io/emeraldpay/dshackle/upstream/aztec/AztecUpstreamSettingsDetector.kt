package io.emeraldpay.dshackle.upstream.aztec

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.upstream.BasicUpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.NodeTypeRequest
import io.emeraldpay.dshackle.upstream.UNKNOWN_CLIENT_VERSION
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Flux

class AztecUpstreamSettingsDetector(
    upstream: Upstream,
) : BasicUpstreamSettingsDetector(upstream) {

    override fun internalDetectLabels(): Flux<Pair<String, String>> {
        return Flux.merge(
            detectNodeType(),
        )
    }

    override fun clientVersionRequest(): ChainRequest {
        return ChainRequest("node_getNodeVersion", ListParams())
    }

    override fun parseClientVersion(data: ByteArray): String {
        var version = String(data).trim()
        if (version.startsWith("\"") && version.endsWith("\"") && version.length >= 2) {
            version = version.substring(1, version.length - 1)
        }
        if (version.startsWith("v") || version.startsWith("V")) {
            version = version.substring(1)
        }
        return version.ifBlank { UNKNOWN_CLIENT_VERSION }
    }

    override fun nodeTypeRequest(): NodeTypeRequest = NodeTypeRequest(clientVersionRequest())

    override fun clientType(node: JsonNode): String = "aztec"

    override fun clientVersion(node: JsonNode): String {
        val raw = when {
            node.isTextual -> node.asText()
            node.isObject -> node.get("nodeVersion")?.asText().orEmpty()
            else -> ""
        }.trim()
        if (raw.isEmpty()) return UNKNOWN_CLIENT_VERSION
        val stripped = if (raw.startsWith("v") || raw.startsWith("V")) raw.substring(1) else raw
        return stripped.ifBlank { UNKNOWN_CLIENT_VERSION }
    }
}
