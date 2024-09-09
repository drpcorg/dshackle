package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.core.type.TypeReference
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamRpcMethodsDetector
import io.emeraldpay.dshackle.upstream.calls.DefaultEthereumMethods
import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Mono

class BasicEthUpstreamRpcMethodsDetector(
    private val upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    override fun detectByMagicMethod(): Mono<Map<String, Boolean>> =
        upstream
            .getIngressReader()
            .read(ChainRequest("rpc_modules", ListParams()))
            .flatMap(ChainResponse::requireResult)
            .map(::parseRpcModules)
            .onErrorResume {
                log.warn("Can't detect rpc_modules of upstream ${upstream.getId()}, reason - {}", it.message)
                Mono.empty()
            }

    override fun rpcMethods(): Set<Pair<String, CallParams>> =
        setOf(
            "eth_getBlockReceipts" to ListParams("latest"),
        )

    private fun parseRpcModules(data: ByteArray): Map<String, Boolean> {
        val modules = Global.objectMapper.readValue(data, object : TypeReference<HashMap<String, String>>() {})
        val allDisabledMethods =
            DefaultEthereumMethods(upstream.getChain()).getSupportedMethods()
                .filter { method ->
                    modules.all { (module, _) -> method.startsWith(module).not() }
                }.associateWith { false }
        // Don't trust the modules, check the methods from rpcMethods
        val rpcMethodsResponse = detectByMethod().block() ?: emptyMap()
        return allDisabledMethods.plus(rpcMethodsResponse)
    }
}
