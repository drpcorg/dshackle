package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.core.type.TypeReference
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamRpcMethodsDetector
import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Mono

// Should be Eth network only?
class BasicEthUpstreamRpcMethodsDetector(
    private val upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    private val methodsToCheck = emptySet<Pair<String, CallParams>>()

    override fun detectByMagicMethod(): Mono<List<String>> =
        upstream
            .getIngressReader()
            .read(ChainRequest("rpc_modules", ListParams()))
            .flatMap(ChainResponse::requireResult)
            .map(::parseRpcModules)
            .onErrorResume {
                log.warn("Can't detect rpc_modules of upstream ${upstream.getId()}, reason - {}", it.message)
                Mono.just(emptyList())
            }

    override fun rpcMethods(): Set<Pair<String, CallParams>> =
        methodsToCheck.plus(
            "eth_getBlockReceipts" to ListParams("latest"),
        )

    private fun parseRpcModules(data: ByteArray): List<String> {
        val modules = Global.objectMapper.readValue(data, object : TypeReference<HashMap<String, String>>() {})
        modules.forEach { (module, _) ->
            //       "debug": "1.0",
            //        "eth": "1.0",
            //        "net": "1.0",
            //        "rpc": "1.0",
            //        "txpool": "1.0",
            //        "web3": "1.0"
//            DefaultEthereumMethods(upstream.getChain()).getGroupMethods(module).forEach {
//                methodsToCheck.plus(it)
//            }
        }
        return emptyList() // Force call detectByMethod
    }
}
