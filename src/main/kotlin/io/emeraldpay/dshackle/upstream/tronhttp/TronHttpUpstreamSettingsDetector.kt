package io.emeraldpay.dshackle.upstream.tronhttp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.BasicUpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.NodeTypeRequest
import io.emeraldpay.dshackle.upstream.UNKNOWN_CLIENT_VERSION
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.RestParams
import reactor.core.publisher.Flux

class TronHttpUpstreamSettingsDetector(
    upstream: Upstream,

) : BasicUpstreamSettingsDetector(upstream) {

    override fun nodeTypeRequest(): NodeTypeRequest {
        return NodeTypeRequest(
            clientVersionRequest(),
        )
    }

    override fun clientVersion(node: JsonNode): String? {
        return node.get("configNodeInfo")?.get("codeVersion")?.asText() ?: ""
    }

    override fun clientType(node: JsonNode): String? {
        return "tron" // api doesn't provide client name
    }

    override fun detectLabels(): Flux<Pair<String, String>> {
        return Flux.merge(
            detectNodeType(),
        )
    }

    override fun clientVersionRequest(): ChainRequest {
        return ChainRequest("GET#/wallet/getnodeinfo", RestParams.emptyParams())
    }

    override fun parseClientVersion(data: ByteArray): String {
        val node = Global.objectMapper.readValue<JsonNode>(data)

        return node.get("configNodeInfo")?.get("codeVersion")?.textValue() ?: UNKNOWN_CLIENT_VERSION
    }
}
