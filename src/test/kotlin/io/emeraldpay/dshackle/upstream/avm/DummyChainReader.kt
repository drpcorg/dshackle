package io.emeraldpay.dshackle.upstream.avm

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import reactor.core.publisher.Mono

object DummyChainReader : ChainReader {
    override fun read(key: ChainRequest): Mono<ChainResponse> = Mono.empty()
}
