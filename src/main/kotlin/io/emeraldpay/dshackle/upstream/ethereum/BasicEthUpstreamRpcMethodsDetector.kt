package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamRpcMethodsDetector
import io.emeraldpay.dshackle.upstream.rpcclient.CallParams
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import reactor.core.publisher.Mono

// Should be Eth network only?
class BasicEthUpstreamRpcMethodsDetector(
    upstream: Upstream,
) : UpstreamRpcMethodsDetector(upstream) {
    override fun detectByMagicMethod(): Mono<List<String>> = Mono.empty()

    override fun rpcMethods(): Set<Pair<String, CallParams>> = setOf("eth_getBlockReceipts" to ListParams("latest"))
}
